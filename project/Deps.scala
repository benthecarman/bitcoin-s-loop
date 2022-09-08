import sbt._

object Deps {

  object V {
    val bitcoinsV = "1.9.3-17-018a6e58-SNAPSHOT"

    val testContainersV = "0.40.10"
  }

  object Compile {

    val bitcoinsLnd =
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTestkit =
      "org.bitcoin-s" %% "bitcoin-s-testkit" % V.bitcoinsV withSources () withJavadoc ()

    val testContainers =
      "com.dimafeng" %% "testcontainers-scala-scalatest" % V.testContainersV withSources () withJavadoc ()
  }

  val loopRpc: List[ModuleID] = List(
    Compile.bitcoinsLnd
  )

  val testkit: List[ModuleID] = List(
    Compile.bitcoinsTestkit,
    Compile.testContainers
  )
}
