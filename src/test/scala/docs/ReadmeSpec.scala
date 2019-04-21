package docs

// scalatest
import org.scalatest._
import Matchers._

// scala
import scalaz.Writer
import scalaz._, Scalaz._

//project
import zipper.{BufferedZipper, Unlimited, SizeLimit}


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
      b <- buffT
      b <- b.nextT
      b <- b.nextT
      b <- b.nextT
      b <- b.nextT
      b <- b.prevT
      b <- b.prevT
      b <- b.prevT
      b <- b.prevT
      b <- b.prevT
    } yield b).run.run._1 shouldBe wordStream.toVector
  }

  it should "repeat effects with a buffer size of 0" in {
    val wordStream = "the effects only happen once"
      .split(" ")
      .toStream

    val writerStream: Stream[OutSim[String]] = wordStream
      .map { s => Vector(s).tell.map(_ => s) }

    val buffT = BufferedZipper.applyT(writerStream, SizeLimit(0))

    (for {
      b <- buffT
      b <- b.nextT
      b <- b.nextT
      b <- b.nextT
      b <- b.nextT
      b <- b.prevT
      b <- b.prevT
      b <- b.prevT
      b <- b.prevT
      b <- b.prevT
    } yield b).run.run._1 shouldBe (wordStream.toVector ++: wordStream.reverse.tail.toVector)
  }

}
