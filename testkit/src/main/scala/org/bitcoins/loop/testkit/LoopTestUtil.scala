package org.bitcoins.loop.testkit

import akka.actor.ActorSystem
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.GenericContainer.FileSystemBind
import grizzled.slf4j.Logging
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.loop._
import org.bitcoins.rpc.client.v23.BitcoindV23RpcClient
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.util.{FileUtil, TestkitBinaries}
import org.testcontainers.Testcontainers
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait

import java.io.{File, PrintWriter}
import java.net.URI
import java.nio.file.Files
import scala.util.Properties

case class LoopFixtureParams(
    bitcoind: BitcoindV23RpcClient,
    serverLnd: LndRpcClient,
    loopLnd: LndRpcClient,
    loop: LoopRpcClient,
    container: GenericContainer)

trait LoopTestUtil extends Logging {

  def randomLoopDatadir(): File =
    new File(s"/tmp/loop-test/${FileUtil.randomDirName}/")

  def cannonicalDatadir = new File(s"${Properties.userHome}/.reg_loop/")

  def createLoopContainer(
      serverInstance: LndInstanceLocal): GenericContainer = {
    val fileBind = FileSystemBind(
      serverInstance.datadir.toAbsolutePath.toString,
      "/root/.lnd",
      BindMode.READ_WRITE)

    val uri = serverInstance.rpcUri
    val cmd =
      s"daemon --maxamt=5000000 --lnd.host=host.testcontainers.internal:${uri.getPort} --lnd.macaroondir=/root/.lnd/data/chain/bitcoin/regtest --lnd.tlspath=/root/.lnd/tls.cert"

    Testcontainers.exposeHostPorts(uri.getPort)
    val container = GenericContainer("lightninglabs/loopserver:latest",
                                     exposedPorts = Seq(11009),
                                     waitStrategy = Wait.forListeningPort(),
                                     command = Seq(cmd),
                                     fileSystemBind = Seq(fileBind))

    container.start()

    container
  }

  def getBinary(versionOpt: Option[String]): Option[File] = {
    val versionStr = versionOpt.getOrElse(LoopRpcClient.version)

    val platform =
      if (Properties.isLinux) "linux-amd64"
      else if (Properties.isMac) "darwin-amd64"
      else if (Properties.isWin) "windows-amd64"
      else sys.error(s"Unsupported OS: ${Properties.osName}")

    val path = TestkitBinaries.baseBinaryDirectory
      .resolve("loop")
      .resolve(s"loop-$platform-$versionStr")
      .resolve("loopd")

    if (Files.exists(path)) {
      Some(path.toFile)
    } else {
      None
    }
  }

  def commonConfig(
      lndInstanceLocal: LndInstanceLocal,
      serverUri: URI,
      rpcPort: Int = RpcUtil.randomPort): String = {
    val lndHost =
      if (lndInstanceLocal.rpcUri.getHost == "0.0.0.0") "localhost"
      else lndInstanceLocal.rpcUri.getHost

    s"""
       |debuglevel=debug
       |rpclisten=127.0.0.1:$rpcPort
       |restlisten=127.0.0.1:${RpcUtil.randomPort}
       |network=regtest
       |server.host=${serverUri.getHost}:${serverUri.getPort}
       |server.notls=1
       |lnd.host=$lndHost:${lndInstanceLocal.rpcUri.getPort}
       |lnd.macaroonpath=${lndInstanceLocal.macaroonPath}
       |lnd.tlspath=${lndInstanceLocal.certFile}
       |""".stripMargin
  }

  def loopDataDir(
      lndInstanceLocal: LndInstanceLocal,
      serverUri: URI,
      isCannonical: Boolean): File = {
    if (isCannonical) {
      // assumes that the ${HOME}/.reg_loop/loopd.conf file is created AND a lnd instance is running
      cannonicalDatadir
    } else {
      // creates a random loop datadir, but still assumes that a lnd instance is running right now
      val datadir = randomLoopDatadir()
      datadir.mkdirs()
      logger.trace(s"Creating temp loop dir ${datadir.getAbsolutePath}")

      val config = commonConfig(lndInstanceLocal, serverUri)

      new PrintWriter(new File(datadir, "loopd.conf")) {
        write(config)
        close()
      }
      datadir
    }
  }

  def loopInstance(lndInstanceLocal: LndInstanceLocal, serverUri: URI)(implicit
      system: ActorSystem): LoopInstanceLocal = {
    val datadir = loopDataDir(lndInstanceLocal, serverUri, isCannonical = false)
    loopInstance(datadir)
  }

  def loopInstance(datadir: File)(implicit
      system: ActorSystem): LoopInstanceLocal = {
    LoopInstanceLocal.fromDataDir(datadir)
  }
}

object LoopTestUtil extends LoopTestUtil
