package org.bitcoins.loop

import org.bitcoins.core.config._
import scodec.bits._

import java.io.File
import java.net._
import java.nio.file._
import scala.util.Properties

sealed trait LoopInstance {
  def rpcUri: URI
  def macaroon: String
  def serverURIOpt: Option[URI]
  def certFileOpt: Option[File]
  def certificateOpt: Option[String]
}

case class LoopInstanceLocal(
    datadir: Path,
    network: BitcoinNetwork,
    rpcUri: URI,
    serverURIOpt: Option[URI] = None)
    extends LoopInstance {

  override val certificateOpt: Option[String] = None
  override val certFileOpt: Option[File] = Some(certFile)

  def macaroonPath: Path = datadir
    .resolve(LoopInstanceLocal.getNetworkDirName(network))
    .resolve("loop.macaroon")

  private var macaroonOpt: Option[String] = None

  override def macaroon: String = {
    macaroonOpt match {
      case Some(value) => value
      case None =>
        val bytes = Files.readAllBytes(macaroonPath)
        val hex = ByteVector(bytes).toHex

        macaroonOpt = Some(hex)
        hex
    }
  }

  lazy val certFile: File =
    datadir
      .resolve(LoopInstanceLocal.getNetworkDirName(network))
      .resolve("tls.cert")
      .toFile
}

object LoopInstanceLocal {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".loop")

  private[loop] def getNetworkDirName(network: BitcoinNetwork): String = {
    network match {
      case MainNet  => "mainnet"
      case TestNet3 => "testnet"
      case RegTest  => "regtest"
      case SigNet   => "signet"
    }
  }

  def fromDataDir(
      dir: File = DEFAULT_DATADIR.toFile,
      network: BitcoinNetwork): LoopInstanceLocal = {
    require(dir.exists, s"${dir.getPath} does not exist!")
    require(dir.isDirectory, s"${dir.getPath} is not a directory!")

    LoopInstanceLocal(dir.toPath, network, new URI("tcp://localhost:11010"))
  }
}

case class LoopInstanceRemote(
    rpcUri: URI,
    macaroon: String,
    certFileOpt: Option[File],
    certificateOpt: Option[String],
    serverURIOpt: Option[URI] = None)
    extends LoopInstance

object LoopInstanceRemote {

  def apply(
      rpcUri: URI,
      macaroon: String,
      certFile: File): LoopInstanceRemote = {
    LoopInstanceRemote(rpcUri, macaroon, Some(certFile), None)
  }

  def apply(
      rpcUri: URI,
      macaroon: String,
      certificate: String): LoopInstanceRemote = {
    LoopInstanceRemote(rpcUri, macaroon, None, Some(certificate))
  }
}
