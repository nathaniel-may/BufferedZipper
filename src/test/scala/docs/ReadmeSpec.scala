package docs

// scalatest
import org.scalatest._
import Matchers._

//project
import zipper.{BufferedZipper, Unlimited, Size}
import util.PropertyFunctions.{Counter, bumpCounter}


class ReadmeSpec extends FlatSpec {

  "A BufferedZipper" should "should not repeat effects with an unlimited buffer" in {
    val ioStream: Stream[Counter[String]] = "the effects only happen once"
      .split(" ")
      .toStream
      .map(bumpCounter)

    val buffT = BufferedZipper.applyT(ioStream, Unlimited)

    (for {
      b  <-  buffT
      n1 <-  b.nextT
      n2 <-  n1.nextT
      n3 <-  n2.nextT
      n4 <-  n3.nextT
      n5 <-  n4.nextT
      p1 <-  n5.prevT
      p2 <-  p1.prevT
      p3 <-  p2.prevT
      p4 <-  p3.prevT
      p5 <-  p4.prevT
    } yield p5).run.exec(0) shouldBe 5
  }

  "A BufferedZipper" should "should repeat effects with a buffer size of 0" in {
    val ioStream: Stream[Counter[String]] = "the effects only happen once"
      .split(" ")
      .toStream
      .map(bumpCounter)

    val buffT = BufferedZipper.applyT(ioStream, Size(0))

    (for {
      b  <-  buffT
      n1 <-  b.nextT
      n2 <-  n1.nextT
      n3 <-  n2.nextT
      n4 <-  n3.nextT
      n5 <-  n4.nextT
      p1 <-  n5.prevT
      p2 <-  p1.prevT
      p3 <-  p2.prevT
      p4 <-  p3.prevT
      p5 <-  p4.prevT
    } yield p5).run.exec(0) shouldBe 10
  }

}
