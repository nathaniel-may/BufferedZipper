package util

// Scalacheck
import org.scalacheck.Prop.{forAll, forAllNoShrink}
import org.scalacheck.{Arbitrary, Properties}
import Generators._
import BufferTypes._

// Scala
import scalaz.Scalaz.Id
import scalaz.effect.IO

// Project
import BufferedZipperFunctions._
import PropertyHelpers._

//TODO add test for buffer eviction in the correct direction ....idk how.
//TODO add test that buffer size should only increase for ints
//TODO add tests for dealing with non-uniform types like strings. What if the first string is larger than the buffer size?
//     -  TODO what if one entry maxes out the buffer size, and the next in focus is smaller than the minimum?
//TODO arch - should test inputs be streams or buffered zippers?
//TODO test BufferedZipper.toList actually minimizes monad effects when focus is at an arbitrary point
object BufferedZipperProperties extends Properties("BufferedZipper") {

  // TODO with path so that buffer gets holes in it and focus isn't always at the head
  // TODO add test for this and effect counter. If something has been pulled into the buffer without being evicted it shouldn't do the effect again to put it into a list
  property("to List returns the same as streamInput.toList") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, None)
      .fold[List[Int]](List())(_.toList) == inStream.toList
  }

  property("list of elements unzipped forwards is the same as the input with no buffer limit") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, None)
      .fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
  }

  // TODO redundant test?
  property("list of elements unzipped forwards is the same as the input with a small buffer limit") =
    forAll(finiteIntStreamGen, cappedBufferSizeGen(300L)) {
      (inStream: Stream[Int], size: CappedBuffer) => BufferedZipper[Id, Int](inStream, Some(size.cap))
        .fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
    }

  property("list of elements unzipped forwards is the same as the input regardless of buffer limit") =
    forAll(intStreamGen, flexibleBufferSizeGen) {
      (inStream: Stream[Int], size: FlexibleBuffer) =>
        BufferedZipper[Id, Int](inStream, Some(size.cap))
          .fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
    }

  property("list of elements unzipping from the back is the same as the input regardless of buffer limit") =
    forAll(intStreamGen, flexibleBufferSizeGen) {
      (inStream: Stream[Int], size: FlexibleBuffer) =>
        BufferedZipper[Id, Int](inStream, Some(size.cap))
          .fold(inStream.isEmpty)(toList(Backwards, _) == inStream.toList)
    }

  property("next then prev should result in the first element regardless of buffer limit") =
    forAll(bZipGenMin[Id, Int](2, flexibleBufferSizeGen)) {
      (zipper: BufferedZipper[Id, Int]) => (for {
        next   <- zipper.next
        prev   <- next.prev
      } yield prev.focus) == zipper.toStream.headOption
    }

  // TODO measureBufferContents is exponential
  property("buffer limit is never exceeded") =
    forAll(intStreamGen, nonZeroBufferSizeGen(16), pathGen) {
      (s: Stream[Int], size: BufferSize, path: Path) =>
        BufferedZipper[Id, Int](s, Some(size.cap))
          .fold(true) { bz => assertAcrossDirections[Id, Int](
            bz,
            path,
            measureBufferContents[Id, Int](_) <= size.cap) }
    }

  property("buffer is being used for streams of at least two elements") =
    forAllNoShrink(streamGenSizeAtLeast(2), nonZeroBufferSizeGen(16), pathGen) {
      (s: Stream[Int], size: BufferSize, path: Path) =>
        BufferedZipper[Id, Int](s, Some(size.cap)).fold[List[Long]](List())(bz => {
          val (f, b, a) = resultsAcrossDirections[Id, Int, Long](
            bz,
            path,
            measureBufferContents[Id, Int])
          println(s"forward  : ${f}") // todo
          println(s"backward : ${b}")
          println(s"arbitrary: ${a}")
          f.drop(1) ::: b.drop(1) ::: a.drop(1) //instead of nonExistent `tailOption`
        }).forall(_ > 0)
    }

  //TODO arbitrary path
  property("buffer is not being used for streams of one or less elements when traversed once forwards") =
    forAll(implicitly[Arbitrary[Option[Int]]].arbitrary, nonZeroBufferSizeGen(16)) {
      (oi: Option[Int], size: LargerBuffer) =>
        BufferedZipper[Id, Int](oi.fold[Stream[Int]](Stream())(Stream(_)), Some(size.cap))
          .fold[List[Long]](List())(bz => unzipAndMap(Forwards, bz, measureBufferContents[Id, Int]))
          .forall(_ == 0)
    }

  // TODO change to arbitrary path
  property("buffer never contains the focus when traversed forwards") =
    forAll(uniqueIntStreamGen, nonZeroBufferSizeGen(16)) {
      (s: Stream[Int], size: LargerBuffer) =>
        BufferedZipper(s, Some(size.cap))
          .fold[List[Boolean]](List())(in => unzipAndMap[Id, Int, Boolean](Forwards, in, bs => bufferContains(bs, bs.focus)))
          .forall(_ == false)
    }

  // TODO change to arbitrary path
  property("buffer limit is never exceeded when traversed forwards") =
    forAll(intStreamGen, nonZeroBufferSizeGen(16)) {
      (s: Stream[Int], size: LargerBuffer) =>
        BufferedZipper[Id, Int](s, Some(size.cap))
          .fold[List[Long]](List())(unzipAndMap[Id, Int, Long](Forwards, _, measureBufferContents[Id, Int]))
          .forall(_ <= size.cap)
    }
 
  // TODO replace var with state monad
  property("effect only takes place when focus called with a stream of one element regardless of buffer size") =
    forAll(implicitly[Arbitrary[Short]].arbitrary, flexibleBufferSizeGen) {
      (elem: Short, size: FlexibleBuffer) => {
        var outsideState: Long = 0
        val instructions = Stream(elem).map(i => IO{ outsideState += i; outsideState })
        val io = for {
          mBuff <- BufferedZipper[IO, Long](instructions, Some(size.cap))
          focus =  for { buff <- mBuff } yield buff.focus
        } yield focus
        val      sameBeforeCall = outsideState == 0
        lazy val sameAfterCall  = outsideState == elem
        io.map { _.unsafePerformIO() }

        sameBeforeCall && sameAfterCall
      }
    }

  // TODO pathify with random starting point?
  property("effect doesn't happen while the buffer contains everything") = forAll {
    inStream: Stream[Int] => {
      var outsideState: Int = 0
      val inStreamWithEffect = inStream.map( i => IO {outsideState += 1; i} )
      val sameContents = BufferedZipper[IO, Int](inStreamWithEffect, None)
        .fold(inStream.isEmpty)(_.flatMap(toList(Backwards, _)).unsafePerformIO() == inStream.toList)
      sameContents && outsideState == inStream.size
    }
  }

}