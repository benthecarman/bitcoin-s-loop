package org.bitcoins.loop.testkit

import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.loop.LoopRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import java.net.URI
import scala.concurrent.duration.DurationInt

trait LoopFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with LoopTestUtil {

  override type FixtureParam = LoopFixtureParams

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[FixtureParam](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          _ = logger.debug("creating lnds")
          (serverLnd, loopLnd) <- LndRpcTestUtil.createNodePair(bitcoind)
          serverInstance = serverLnd.instance.asInstanceOf[LndInstanceLocal]
          container = createLoopContainer(serverInstance)
          // wait for loop server to start up
          _ <- TestAsyncUtil.nonBlockingSleep(30.seconds)
          host = container.host
          port = container.mappedPort(11009)
          serverUri = new URI(s"tcp://$host:$port")

          instance = loopInstance(
            loopLnd.instance.asInstanceOf[LndInstanceLocal],
            serverUri)

          loop = new LoopRpcClient(instance, getBinary(None))

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
}
