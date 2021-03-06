package io.github.novakovalexey.krboperator

import java.nio.file.Path

import cats.Parallel
import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.models.{CustomResource, Metadata, NewStatus}
import freya.{Controller, CrdHelper}
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.krboperator.PrincipalsController.ServerLabel
import io.github.novakovalexey.krboperator.ServerController.checkMark
import io.github.novakovalexey.krboperator.Utils.{logDebugWithNamespace, logInfoWithNamespace}
import io.github.novakovalexey.krboperator.service.{Kadmin, KadminContext, KerberosState, Secrets}

object PrincipalsController {
  val ServerLabel = "krb-operator.novakov-alexey.github.io/server"
}

class PrincipalsController[F[_]: Parallel](
  serverHelper: CrdHelper[F, KrbServer, KrbServerStatus],
  client: OpenShiftClient,
  secret: Secrets[F],
  kadmin: Kadmin[F],
  operatorCfg: KrbOperatorCfg,
  parallelSecret: Boolean = true
)(implicit F: Sync[F])
    extends Controller[F, Principals, PrincipalsStatus]
    with LazyLogging {
  private val debug = logDebugWithNamespace(logger)
  private val info = logInfoWithNamespace(logger)

  override def onAdd(resource: CustomResource[Principals, PrincipalsStatus]): F[NewStatus[PrincipalsStatus]] =
    onApply(resource.spec, resource.metadata)

  override def onModify(resource: CustomResource[Principals, PrincipalsStatus]): F[NewStatus[PrincipalsStatus]] =
    onApply(resource.spec, resource.metadata)

  override def reconcile(resource: CustomResource[Principals, PrincipalsStatus]): F[NewStatus[PrincipalsStatus]] =
    onApply(resource.spec, resource.metadata)

  override def onDelete(resource: CustomResource[Principals, PrincipalsStatus]): F[Unit] =
    F.delay(info(resource.metadata.namespace, s"delete event: ${resource.spec}, ${resource.metadata}")) *> secret
      .delete(resource.metadata.namespace)

  private def onApply(principals: Principals, meta: Metadata) =
    for {
      server <- getKrbServer(meta)
      missingSecrets <- secret.findMissing(meta, principals.list.map(_.secret.name).toSet)
      created <- missingSecrets.toList match {
        case Nil =>
          F.delay(debug(meta.namespace, s"There are no missing secrets")) *> F.pure(List.empty[Unit])
        case _ =>
          F.delay(
            info(meta.namespace, s" There are ${missingSecrets.size} missing secrets, name(s): $missingSecrets")
          ) *> createSecrets(server, principals, meta, missingSecrets)
      }
      _ <- F.whenA(created.nonEmpty)(F.delay(info(meta.namespace, s"${created.length} secrets created")))
    } yield PrincipalsStatus(processed = true, created.length, principals.list.length).some

  private[krboperator] def getKrbServer(meta: Metadata): F[CustomResource[KrbServer, KrbServerStatus]] = for {
    serverName <- F.fromEither(meta.labels.collectFirst { case (ServerLabel, v) => v }
      .toRight(new RuntimeException(s"Current resource does not have a label '$ServerLabel'")))
    servers <- F.fromEither(serverHelper.currentResources())
    serverOrError = servers.find { r =>
      r match {
        case Left(_) => false
        case Right(cr) => cr.metadata.name == serverName
      }
    }.map(_.leftMap(_._1))
      .getOrElse(
        Either.left(
          new RuntimeException(s"Failed to find ${classOf[KrbServer].getSimpleName()} resource with name '$serverName'")
        )
      )
    server <- F.fromEither(serverOrError)
  } yield server

  private def createSecrets(
    server: CustomResource[KrbServer, KrbServerStatus],
    principals: Principals,
    meta: Metadata,
    missingSecrets: Set[String]
  ) =
    for {
      pwd <- secret.getAdminPwd(meta)
      context = KadminContext(server.metadata.name, server.spec.realm, meta, pwd)
      created <- {
        val tasks = missingSecrets
          .map(s => (s, principals.list.filter(_.secret.name == s)))
          .map { case (secretName, principals) =>
            for {
              _ <- F.delay(debug(meta.namespace, s"Creating secret: $secretName"))
              state <- kadmin.createPrincipalsAndKeytabs(principals, context)
              statuses <- copyKeytabs(meta.namespace, state)
              _ <- checkStatuses(statuses)
              _ <- secret.create(meta.namespace, state.principals, secretName)
              _ = info(meta.namespace, s"$checkMark Keytab secret '$secretName' created")
              _ <- removeWorkingDirs(meta.namespace, state).handleError { e =>
                logger
                  .error(
                    s"Failed to delete working directory(s) with keytab(s) in POD ${meta.namespace}/${state.podName}",
                    e
                  )
              }
            } yield ()
          }
          .toList
        if (parallelSecret) tasks.parSequence else tasks.sequence
      }
    } yield created

  private def checkStatuses(statuses: List[(Path, Boolean)]) = {
    val notAllCopied = !statuses.forall { case (_, copied) => copied }
    F.whenA(notAllCopied)(F.raiseError[Unit] {
      val paths = statuses.filter { case (_, copied) =>
        !copied
      }.map { case (path, _) => path }
      new RuntimeException(s"Failed to upload keytab(s) $paths into POD")
    })
  }

  private def copyKeytabs(namespace: String, state: KerberosState): F[List[(Path, Boolean)]] =
    F.delay(state.principals.foldLeft(List.empty[(Path, Boolean)]) { case (acc, principals) =>
      val path = principals.keytabMeta.path
      debug(namespace, s"Copying keytab '$path' from $namespace/${state.podName} POD")
      val copied = client.pods
        .inNamespace(namespace)
        .withName(state.podName)
        .inContainer(operatorCfg.kadminContainer)
        .file(path.toString)
        .copy(path)

      acc :+ (path, copied)
    })

  private def removeWorkingDirs(namespace: String, state: KerberosState): F[Unit] =
    state.principals.map { p =>
      kadmin.removeWorkingDir(namespace, state.podName, p.keytabMeta.path)
    }.sequence.void
}
