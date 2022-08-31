package org.bitcoins.loop

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.GenericContainer.FileSystemBind
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.util.NetworkUtil.randomPort
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.rpc.client.v23.BitcoindV23RpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.bitcoins.testkit.util.{FileUtil, TestkitBinaries}
import org.scalatest.FutureOutcome
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait

import java.io.File
import java.net.URI
import java.nio.file.Files
import scala.concurrent.duration.DurationInt
import scala.util.Properties

case class LoopFixtureParams(
    bitcoind: BitcoindV23RpcClient,
    serverLnd: LndRpcClient,
    loopLnd: LndRpcClient,
    loop: LoopRpcClient,
    container: GenericContainer)

trait LoopFixture extends BitcoinSFixture with CachedBitcoindV23 {

  override type FixtureParam = LoopFixtureParams

  def randomLoopDatadir(): File =
    new File(s"/tmp/loop-test/${FileUtil.randomDirName}/")

  private def createLoopContainer(lnd: LndRpcClient): GenericContainer = {
    val serverInstance = lnd.instance.asInstanceOf[LndInstanceLocal]

    val fileBind = FileSystemBind(
      serverInstance.datadir.toAbsolutePath.toString,
      "/root/.lnd",
      BindMode.READ_WRITE)

    val uri = serverInstance.rpcUri
    val cmd =
      s"daemon --maxamt=5000000 --lnd.host=${uri.getHost}:${uri.getPort} --lnd.macaroondir=/root/.lnd/data/chain/bitcoin/regtest --lnd.tlspath=/root/.lnd/tls.cert"

    val container = GenericContainer("lightninglabs/loopserver:latest",
                                     exposedPorts = Seq(11009),
                                     waitStrategy = Wait.forListeningPort(),
                                     command = Seq(cmd),
                                     fileSystemBind = Seq(fileBind))

    container.start()

    container
  }

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[FixtureParam](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          _ = println("creating lnds")
          _ = logger.debug("creating lnds")
          (serverLnd, loopLnd) <- LndRpcTestUtil.createNodePair(bitcoind)
          _ = println("creating container")
          container = createLoopContainer(serverLnd)
          port = container.mappedPort(11009)
          serverUri = Some(new URI(s"tcp://127.0.0.1:$port"))
          _ = println("creating loop client")

          loopPort = randomPort()
          _ = println(s"loopPort: $loopPort")
          _ = println(s"serverUri: $serverUri")
          instance =
            LoopInstanceLocal(datadir = randomLoopDatadir().toPath,
                              network = RegTest,
                              rpcUri = new URI(s"tcp://127.0.0.1:$loopPort"),
                              serverURIOpt = serverUri)

          loop = new LoopRpcClient(loopLnd, instance, getBinary(None))

          _ <- loop.start()

          // Wait for loopd to be ready
          _ <- TestAsyncUtil.awaitConditionF(() => loop.isStarted,
                                             interval = 500.milliseconds,
                                             maxTries = 100)

        } yield LoopFixtureParams(bitcoind, serverLnd, loopLnd, loop, container)
      },
      { case LoopFixtureParams(_, lndA, lndB, loop, container) =>
        container.stop()
        for {
          _ <- loop.stop()
          _ <- lndA.stop()
          _ <- lndB.stop()
        } yield ()
      }
    )(test)
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
}
