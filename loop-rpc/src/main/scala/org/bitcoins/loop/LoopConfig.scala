package org.bitcoins.loop

import grizzled.slf4j.Logging
import org.bitcoins.core.api.commons.ConfigFactory
import org.bitcoins.core.config._
import scodec.bits.ByteVector

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.util.Properties

/** This class represents a parsed `loopd.conf` file. It respects the different
  * ways of writing options in `loopd.conf`: Raw options, network-prefixed
  * options and options within network sections. It also tries to conform to the
  * way loopd gives precedence to the different properties.
  *
  * Not all options are exposed from this class. We only expose those that are
  * of relevance when making RPC requests.
  */
case class LoopConfig(private[bitcoins] val lines: Seq[String], datadir: File)
    extends Logging {

  // create datadir and config if it DNE on disk
  if (!datadir.exists()) {
    logger.debug(
      s"datadir=${datadir.getAbsolutePath} does not exist, creating now")
    datadir.mkdirs()
    LoopConfig.writeConfigToFile(this, datadir)
  }

  private val confFile = datadir.toPath.resolve("loopd.conf")

  // create loopd.conf file in datadir if it does not exist
  if (!Files.exists(confFile)) {
    logger.debug(
      s"loopd.conf in datadir=${datadir.getAbsolutePath} does not exist, creating now")
    LoopConfig.writeConfigToFile(this, datadir)
  }

  /** Converts the config back to a string that can be written to file, and
    * passed to `loopd`
    */
  lazy val toWriteableString: String = lines.mkString("\n")

  /** Splits the provided lines into pairs of keys/values based on `=`, and then
    * applies the provided `collect` function on those pairs
    */
  private def collectFrom(lines: Seq[String])(
      collect: PartialFunction[(String, String), String]): Seq[String] = {

    val splittedPairs = {
      val splitLines = lines.map(
        _.split("=")
          .map(_.trim)
          .toList)

      splitLines.collect { case h :: t :: _ =>
        h -> t
      }
    }

    splittedPairs.collect(collect)
  }

  private[loop] def getValue(key: String): Option[String] = {
    val linesToSearchIn =
      lines.takeWhile(l => !l.trim.startsWith("[") || !l.trim.startsWith(";"))
    val collect = collectFrom(linesToSearchIn)(_)
    collect { case (`key`, value) =>
      value
    }.headOption
  }

  lazy val rpcBinding: URI = new URI({
    val baseUrl = getValue("rpclisten").getOrElse("127.0.0.1:11010")
    if (baseUrl.startsWith("http")) baseUrl
    else "http://" + baseUrl
  })

  lazy val restBinding: URI = new URI({
    val baseUrl = getValue("restlisten").getOrElse("127.0.0.1:8081")
    if (baseUrl.startsWith("http")) baseUrl
    else "http://" + baseUrl
  })

  lazy val network: BitcoinNetwork = {
    val networkStr = getValue("network").getOrElse("mainnet")
    networkStr match {
      case "mainnet" => MainNet
      case "testnet" => TestNet3
      case "regtest" => RegTest
      case "signet"  => SigNet
      case _ =>
        throw new IllegalArgumentException(
          s"Invalid network=$networkStr, must be one of mainnet, testnet, regtest, signet")
    }
  }

  /** Creates a new config with the given keys and values appended */
  def withOption(key: String, value: String): LoopConfig = {
    val ourLines = this.lines
    val newLine = s"$key=$value"
    val lines = newLine +: ourLines
    val newConfig = LoopConfig(lines, datadir)
    logger.debug(
      s"Appending new config with $key=$value to datadir=${datadir.getAbsolutePath}")
    LoopConfig.writeConfigToFile(newConfig, datadir)

    newConfig
  }

  def withDatadir(newDatadir: File): LoopConfig = {
    LoopConfig(lines, newDatadir)
  }

  lazy val loopInstance: LoopInstanceLocal = LoopInstanceLocal(
    datadir.toPath,
    network,
    rpcBinding
  )

  lazy val loopInstanceRemote: LoopInstanceRemote = {
    val netDir =
      datadir.toPath.resolve(LoopInstanceLocal.getNetworkDirName(network))

    val macaroon = {
      val path = netDir.resolve("loop.macaroon")
      val bytes = Files.readAllBytes(path)
      ByteVector(bytes).toHex
    }

    val cert = netDir.resolve("tls.cert").toFile

    LoopInstanceRemote(rpcBinding, macaroon, cert)
  }
}

object LoopConfig extends ConfigFactory[LoopConfig] with Logging {

  /** The empty `loop` config */
  override lazy val empty: LoopConfig = LoopConfig("", DEFAULT_DATADIR)

  /** Constructs a `loop` config from the given string, by splitting it on
    * newlines
    */
  override def apply(config: String, datadir: File): LoopConfig =
    apply(config.split("\n").toList, datadir)

  /** Reads the given path and construct a `loop` config from it */
  override def apply(config: Path): LoopConfig =
    apply(config.toFile, config.getParent.toFile)

  /** Reads the given file and construct a `loop` config from it */
  override def apply(
      config: File,
      datadir: File = DEFAULT_DATADIR): LoopConfig = {
    import org.bitcoins.core.compat.JavaConverters._
    val lines = Files
      .readAllLines(config.toPath)
      .iterator()
      .asScala
      .toList

    apply(lines, datadir)
  }

  override def fromConfigFile(file: File): LoopConfig = {
    apply(file.toPath)
  }

  override def fromDataDir(dir: File): LoopConfig = {
    apply(dir.toPath.resolve("loopd.conf"))
  }

  /** If there is a `loopd.conf` in the default data directory, this is read.
    * Otherwise, the default configuration is returned.
    */
  override def fromDefaultDatadir: LoopConfig = {
    if (DEFAULT_CONF_FILE.isFile) {
      apply(DEFAULT_CONF_FILE)
    } else {
      LoopConfig.empty
    }
  }

  override val DEFAULT_DATADIR: File = {
    val path = if (Properties.isMac) {
      Paths.get(Properties.userHome, "Library", "Application Support", "Loop")
    } else if (Properties.isWin) {
      Paths.get("C:", "Users", Properties.userName, "Appdata", "Local", "Loop")
    } else {
      Paths.get(Properties.userHome, ".loop")
    }
    path.toFile
  }

  /** Default location of loopd conf file */
  override val DEFAULT_CONF_FILE: File = DEFAULT_DATADIR.toPath
    .resolve("loopd.conf")
    .toFile

  /** Writes the config to the data directory within it, if it doesn't exist.
    * Returns the written file.
    */
  override def writeConfigToFile(config: LoopConfig, datadir: File): Path = {

    val confStr = config.lines.mkString("\n")

    Files.createDirectories(datadir.toPath)
    val confFile = datadir.toPath.resolve("loopd.conf")

    if (datadir == DEFAULT_DATADIR && confFile == DEFAULT_CONF_FILE.toPath) {
      logger.warn(
        s"We will not overwrite the existing loopd.conf in default datadir")
    } else {
      Files.write(confFile, confStr.getBytes)
    }

    confFile
  }
}
