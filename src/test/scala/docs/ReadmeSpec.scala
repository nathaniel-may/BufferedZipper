package docs

// scalatest
import org.scalatest._
import Matchers._

// scala
import scalaz.Writer
import scalaz._, Scalaz._

//project
import zipper.{BufferedZipper, Unlimited, Size}


class ReadmeSpec extends FlatSpec {
  type OutSim[A] = Writer[Vector[String], A]

  "A BufferedZipper" should "should not repeat effects with an unlimited buffer" in {
    val wordStream = "the effects only happen once"
      .split(" ")
      .toStream

    val writerStream: Stream[OutSim[String]] = wordStream
      .map { s => Vector(s).tell.map(_ => s) }

    val buffT = BufferedZipper.applyT(writerStream, Unlimited)

    (for {
      b  <- buffT
      n1 <- b.nextT
      n2 <- n1.nextT
      n3 <- n2.nextT
      n4 <- n3.nextT
      p1 <- n4.prevT
      p2 <- p1.prevT
      p3 <- p2.prevT
      p4 <- p3.prevT
      p5 <- p4.prevT
    } yield p5).run.run._1 shouldBe wordStream.toVector
  }

  it should "repeat effects with a buffer size of 0" in {
    val wordStream = "the effects only happen once"
      .split(" ")
      .toStream

    val writerStream: Stream[OutSim[String]] = wordStream
      .map { s => Vector(s).tell.map(_ => s) }

    val buffT = BufferedZipper.applyT(writerStream, Size(0))

    (for {
      b  <- buffT
      n1 <- b.nextT
      n2 <- n1.nextT
      n3 <- n2.nextT
      n4 <- n3.nextT
      p1 <- n4.prevT
      p2 <- p1.prevT
      p3 <- p2.prevT
      p4 <- p3.prevT
      p5 <- p4.prevT
    } yield p5).run.run._1 shouldBe (wordStream.toVector ++: wordStream.reverse.tail.toVector)
  }

}
