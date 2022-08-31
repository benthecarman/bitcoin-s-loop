import sbt._

object Projects {
  val `bitcoin-s-loop` = project in file("..")
  val loopRpc = project in file("..") / "loop-rpc"
}
