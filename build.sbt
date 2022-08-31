ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

lazy val `bitcoin-s-loop` = project
  .in(file("."))
  .aggregate(
    loopRpc,
    loopRpcTest,
    testkit
  )
  .dependsOn(
    loopRpc,
    loopRpcTest,
    testkit
  )
  .settings(CommonSettings.settings: _*)
  .settings(
    name := "bitcoin-s-loop",
    publish / skip := true
  )

lazy val loopRpc = project
  .in(file("loop-rpc"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "loop-rpc", libraryDependencies ++= Deps.loopRpc)

lazy val loopRpcTest = project
  .in(file("loop-rpc-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "loop-rpc-test")
  .dependsOn(loopRpc, testkit)

lazy val testkit = project
  .in(file("testkit"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "testkit", libraryDependencies ++= Deps.testkit)
  .dependsOn(loopRpc)
