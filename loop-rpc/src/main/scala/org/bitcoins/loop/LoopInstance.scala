package org.bitcoins.loop

import akka.actor.ActorSystem
import org.bitcoins.core.api.commons.InstanceFactoryLocal
import org.bitcoins.core.config._
import scodec.bits._

import java.io.File
import java.net._
import java.nio.file._
import scala.util.Properties

sealed trait LoopInstance {
  def rpcUri: URI
  def macaroon: String
  def certFileOpt: Option[File]
  def certificateOpt: Option[String]
}

case class LoopInstanceLocal(
    datadir: Path,
    network: BitcoinNetwork,
    rpcUri: URI)
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

object LoopInstanceLocal
    extends InstanceFactoryLocal[LoopInstanceLocal, ActorSystem] {

  override val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".loop")

  override val DEFAULT_CONF_FILE: Path = DEFAULT_DATADIR.resolve("loopd.conf")

  private[loop] def getNetworkDirName(network: BitcoinNetwork): String = {
    network match {
      case _: MainNet  => "mainnet"
      case _: TestNet3 => "testnet"
      case _: RegTest  => "regtest"
      case _: SigNet   => "signet"
    }
  }

  override def fromConfigFile(file: File = DEFAULT_CONF_FILE.toFile)(implicit
      system: ActorSystem): LoopInstanceLocal = {
    require(file.exists, s"${file.getPath} does not exist!")
    require(file.isFile, s"${file.getPath} is not a file!")

    val config = LoopConfig(file, file.getParentFile)

    fromConfig(config)
  }

  override def fromDataDir(dir: File = DEFAULT_DATADIR.toFile)(implicit
      system: ActorSystem): LoopInstanceLocal = {
    require(dir.exists, s"${dir.getPath} does not exist!")
    require(dir.isDirectory, s"${dir.getPath} is not a directory!")

    val confFile = dir.toPath.resolve("loopd.conf").toFile
    val config = LoopConfig(confFile, dir)

    fromConfig(config)
  }

  def fromConfig(config: LoopConfig): LoopInstanceLocal = {
    config.loopInstance
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
