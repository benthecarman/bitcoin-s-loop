package org.bitcoins.loop

import akka.actor.ActorSystem
import akka.grpc.{GrpcClientSettings, SSLContextUtils}
import grizzled.slf4j.Logging
import io.grpc.{CallCredentials, Metadata}
import org.bitcoins.commons.util.NativeProcessFactory
import org.bitcoins.lnd.rpc.{LndRpcClient, LndUtils}
import org.bitcoins.lnd.rpc.config._
import looprpc._
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.util.StartStopAsync

import java.io._
import java.util.concurrent.Executor
import scala.concurrent._
import scala.util._

/** @param binaryOpt
  *   Path to loopd executable
  */
class LoopRpcClient(
    val lnd: LndRpcClient,
    val instance: LoopInstance,
    binaryOpt: Option[File] = None)(implicit val system: ActorSystem)
    extends NativeProcessFactory
    with LndUtils
    with StartStopAsync[LoopRpcClient]
    with Logging {

  instance match {
    case _: LoopInstanceLocal =>
      require(binaryOpt.isDefined,
              s"Binary must be defined with a local instance of loop")
    case _: LoopInstanceRemote => ()
  }

  /** The command to start the daemon on the underlying OS */
  override lazy val cmd: String = {
    instance match {
      case local: LoopInstanceLocal =>
        val dir = s"--loopdir=${local.datadir.toAbsolutePath}"
        val listen =
          s"--rpclisten=${instance.rpcUri.getHost}:${instance.rpcUri.getPort}"

        val lndUri =
          s"--lnd.host=${lnd.instance.rpcUri.getHost}:${lnd.instance.rpcUri.getPort}"

        val (lndMacPath, lndTlsCert) = lnd.instance match {
          case lndLocal: LndInstanceLocal =>
            val mac = s"--lnd.macaroonpath=${lndLocal.macaroonPath}"
            val tls = s"--lnd.tlspath=${lndLocal.certFile}"
            (mac, tls)
          case _: LndInstanceRemote => ("", "")
        }

        val serverHost = instance.serverURIOpt match {
          case Some(uri) => s"--server.host=${uri.getHost}:${uri.getPort}"
          case None      => ""
        }

        val networkStr = LoopInstanceLocal.getNetworkDirName(local.network)
        val network = s"--network=$networkStr"

        // regtest loop server does not have tls certs
        val noTls =
          if (local.network == RegTest) "--server.notls"
          else ""

        s"${binaryOpt.get} --debuglevel=debug $dir $network $listen $lndUri $lndMacPath $lndTlsCert $serverHost $noTls"
      case _: LoopInstanceRemote => ""
    }
  }

  implicit val executionContext: ExecutionContext = system.dispatcher

  // These need to be lazy so we don't try and fetch
  // the tls certificate before it is generated
  private[this] lazy val certStreamOpt: Option[InputStream] = {
    instance.certFileOpt match {
      case Some(file) => Some(new FileInputStream(file))
      case None =>
        instance.certificateOpt match {
          case Some(cert) =>
            Some(
              new ByteArrayInputStream(
                cert.getBytes(java.nio.charset.StandardCharsets.UTF_8.name)))
          case None => None
        }
    }
  }

  private lazy val callCredentials = new CallCredentials {

    def applyRequestMetadata(
        requestInfo: CallCredentials.RequestInfo,
        appExecutor: Executor,
        applier: CallCredentials.MetadataApplier
    ): Unit = {
      appExecutor.execute(() => {
        // Wrap in a try, in case the macaroon hasn't been created yet.
        Try {
          val metadata = new Metadata()
          val key =
            Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER)
          metadata.put(key, instance.macaroon)
          applier(metadata)
        }
        ()
      })
    }

    def thisUsesUnstableApi(): Unit = ()
  }

  // Configure the client
  lazy val clientSettings: GrpcClientSettings = {
    val trustManagerOpt = certStreamOpt match {
      case Some(stream) => Some(SSLContextUtils.trustManagerFromStream(stream))
      case None         => None
    }

    val client = GrpcClientSettings
      .connectToServiceAt(instance.rpcUri.getHost, instance.rpcUri.getPort)
      .withCallCredentials(callCredentials)

    trustManagerOpt match {
      case Some(trustManager) => client.withTrustManager(trustManager)
      case None               => client
    }
  }

  lazy val swapClient: SwapClientClient = {
    SwapClientClient(clientSettings)
  }
  lazy val swapServer: SwapServerClient = SwapServerClient(clientSettings)

  def getLiquidityParams(): Future[LiquidityParameters] = {
    swapClient.getLiquidityParams(GetLiquidityParamsRequest())
  }

  def getLoopInTerms(): Future[InTermsResponse] = {
    swapClient.getLoopInTerms(TermsRequest())
  }

  /** Starts lnd on the local system.
    */
  override def start(): Future[LoopRpcClient] = {
    startBinary().map(_ => this)
  }

  /** Boolean check to verify the state of the client
    *
    * @return
    *   Future Boolean representing if client has started
    */
  def isStarted: Future[Boolean] = {
    val p = Promise[Boolean]()

    val t = Try(getLiquidityParams().onComplete {
      case Success(_) =>
        p.success(true)
      case Failure(_) =>
        p.success(false)
    })

    t.failed.map { _ =>
      p.success(false)
    }

    p.future
  }

  /** Returns a Future LoopRpcClient if able to shut down loop instance,
    * inherits from the StartStop trait
    *
    * @return
    *   A future LoopRpcClient that is stopped
    */
  override def stop(): Future[LoopRpcClient] = {
    logger.trace("loop calling stop daemon")

    for {
      _ <- stopBinary()
      _ <- {
        if (system.name == LoopRpcClient.ActorSystemName)
          system.terminate()
        else Future.unit
      }
    } yield this
  }

  /** Checks to see if the client stopped successfully
    *
    * @return
    */
  def isStopped: Future[Boolean] = {
    isStarted.map(started => !started)
  }
}

object LoopRpcClient {

  /** The current version we support of loopd */
  private[bitcoins] val version = "v0.20.1-beta"

  /** THe name we use to create actor systems. We use this to know which actor
    * systems to shut down on node shutdown
    */
  private[loop] val ActorSystemName = "loop-rpc-client-created-by-bitcoin-s"

  /** Creates an RPC client from the given instance, together with the given
    * actor system. This is for advanced users, where you need fine grained
    * control over the RPC client.
    */
  def apply(
      lnd: LndRpcClient,
      instance: LoopInstance,
      binary: Option[File] = None): LoopRpcClient = {
    implicit val system: ActorSystem = ActorSystem.create(ActorSystemName)
    withActorSystem(lnd, instance, binary)
  }

  /** Constructs a RPC client from the given datadir, or the default datadir if
    * no directory is provided
    */
  def withActorSystem(
      lnd: LndRpcClient,
      instance: LoopInstance,
      binary: Option[File] = None)(implicit
      system: ActorSystem): LoopRpcClient = {
    new LoopRpcClient(lnd, instance, binary)
  }
}
