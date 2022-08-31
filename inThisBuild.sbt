import sbt.Keys.excludeLintKeys
import xerial.sbt.Sonatype.GitHubHosting

import scala.util.Properties

val scala2_12 = "2.12.15"
val scala2_13 = "2.13.8"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/benthecarman/bitcoin-s-loop"),
    "scm:git@github.com:benthecarman/bitcoin-s-loop.git"
  )
)

ThisBuild / developers := List(
  Developer(
    "benthecarman",
    "benthecarman",
    "benthecarman@live.com",
    url("https://twitter.com/benthecarman")
  )
)

ThisBuild / organization := "org.bitcoin-s.loop"

ThisBuild / licenses := List(
  "MIT" -> new URL("https://opensource.org/licenses/MIT"))

ThisBuild / homepage := Some(
  url("https://github.com/benthecarman/bitcoin-s-loop"))

ThisBuild / sonatypeProfileName := "org.bitcoin-s.loop"

ThisBuild / sonatypeProjectHosting := Some(
  GitHubHosting("benthecarman", "bitcoin-s-loop", "benthecarman@live.com"))

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

ThisBuild / scalafmtOnCompile := !Properties.envOrNone("CI").contains("true")

ThisBuild / scalaVersion := scala2_13

ThisBuild / crossScalaVersions := List(scala2_13, scala2_12)

ThisBuild / dynverSeparator := "-"

//https://github.com/sbt/sbt/pull/5153
//https://github.com/bitcoin-s/bitcoin-s/pull/2194
Global / excludeLintKeys ++= Set(
  com.typesafe.sbt.packager.Keys.maintainer,
  Keys.mainClass
)
