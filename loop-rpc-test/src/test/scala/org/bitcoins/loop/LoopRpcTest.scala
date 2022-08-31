package org.bitcoins.loop

class LoopRpcTest extends LoopFixture {

  it must "get swap info" in { params =>
    for {
      terms <- params.loop.getLoopInTerms()
    } yield {
      assert(terms.maxSwapAmount >= terms.minSwapAmount)
    }
  }
}
