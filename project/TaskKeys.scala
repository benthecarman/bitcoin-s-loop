import sbt._

object TaskKeys {

  lazy val downloadBitcoind = taskKey[Unit] {
    "Download bitcoind binaries, extract to ./binaries/bitcoind"
  }

  lazy val downloadLnd = taskKey[Unit] {
    "Download lnd binaries, extract ./binaries/lnd"
  }

  lazy val downloadLoop = taskKey[Unit] {
    "Download loop binaries, extract ./binaries/loop"
  }
}
