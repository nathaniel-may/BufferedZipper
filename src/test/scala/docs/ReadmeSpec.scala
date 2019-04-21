package docs

// scalatest
import org.scalatest._
import Matchers._

// scala
import cats.effect.IO
import cats.data.Writer
import cats.implicits._

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
    } yield b).value.run._1 shouldBe wordStream.toVector
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
    } yield b).value.run._1 shouldBe (wordStream.toVector ++: wordStream.reverse.tail.toVector)
  }

  it should "compile and run with the IO Monad instead of the Writer monad" in {
    val wordStream = "the effects only happen once"
      .split(" ")
      .toStream

    val ioStream: Stream[IO[String]] = wordStream
      .map { s => IO { println(s); s } }

    val buffT = BufferedZipper.applyT(ioStream, Unlimited)

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
    } yield b).value.unsafeRunSync()

    true
  }
}
