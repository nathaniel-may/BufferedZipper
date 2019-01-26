package util

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Properties, Test}
import Arbitrary.{arbLong, arbOption}
import org.scalacheck.Test.Parameters
import testingUtil.Arbitrarily.boundedStreamAndPathsGen

// Scala
import scalaz.Monad
import scalaz.Scalaz.Id
import scalaz.effect.IO

// Project
import testingUtil.BufferedZipperFunctions._
import testingUtil.Arbitrarily._

//TODO add test for buffer eviction in the correct direction ....idk how.
object BufferedZipperProperties extends Properties("BufferedZipper") {

  val optLongGen: Gen[Option[Long]] = arbOption[Long](arbLong).arbitrary

  // TODO with path so that buffer gets holes in it and focus isn't always at the head
  // TODO add test for this and effect counter. If something has been pulled into the buffer without being evicted it shouldn't do the effect again to put it into a list
  property("to List returns the same as streamInput.toList") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, None)
      .fold[List[Int]](List())(_.toList) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with no buffer limit") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, None)
      .fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a small buffer limit") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, Some(100L))
      .fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a buffer limit of 0") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, Some(0L))
      .fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      BufferedZipper[Id, Int](inStream, max).fold[List[Int]](List())(toList(Forwards, _)) == inStream.toList
  }

  property("next then prev should result in the first element regardless of buffer limit") =
    forAll(boundedStreamAndPathsGen(Some(2), None), optLongGen) {
      (sp: StreamAndPaths[Id, Int], max: Option[Long]) => (for {
        zipper <- BufferedZipper[Id, Int](sp.stream, max)
        next   <- zipper.next
        prev   <- next.prev
      } yield prev.focus) == sp.stream.headOption
    }

  property("list of elements unzipping from the back is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      BufferedZipper[Id, Int](inStream, max)
        .fold(inStream.isEmpty)(toList(Backwards, _) == inStream.toList)
  }

  // TODO measureBufferContents is exponential
//  property("buffer limit is never exceeded") = forAll(streamAndPathsGen, nonZeroBufferSizeGen(16)) {
//    implicit val params: Test.Parameters = Parameters.default.withMinSize(10)
//    (sp: StreamAndPaths[Id, Int], size: BufferSize) => forAll(sp.pathGen) { path: Path =>
//      BufferedZipper[Id, Int](sp.stream, size.max)
//        .fold[List[Long]](List())(bz => unzipAndMapViaPath(bz, measureBufferContents[Id, Int], path.steps))
//        .forall(_ <= size.max.get) }
//  }
//
//  //TODO THIS IS THE ONE I WAS WORKING ON
//  property("buffer is being used for streams of at least two elements") =
//    forAll(boundedStreamAndPathsGen(Some(2), None), nonZeroBufferSizeGen(16)) {
//      implicit val params: Test.Parameters = Parameters.default.withMinSize(10)
//      (sp: StreamAndPaths[Id, Int], nonZeroSize: BufferSize) => forAll(sp.pathGen) { path: Path =>
//        BufferedZipper[Id, Int](sp.stream, nonZeroSize.max)
//          .fold[List[Long]](List())(bz => unzipAndMapViaPath(bz, measureBufferContents[Id, Int], path.steps))
//          .tail.forall(_ > 0) }
//    }

  property("buffer is not being used for streams of one or less elements when traversed once forwards") =
    forAll(boundedStreamAndPathsGen(Some(0), Some(1)), nonZeroBufferSizeGen(16)) {
      implicit val params: Test.Parameters = Parameters.default.withMinSize(10)
      (sp: StreamAndPaths[Id, Int], size: BufferSize) => forAll(sp.pathGen) { path: Path =>
        BufferedZipper[Id, Int](sp.stream, size.max)
          .fold[List[Long]](List())(bz => unzipAndMapViaPath(bz, measureBufferContents[Id, Int], path.steps))
          .forall(_ == 0) }
  }

  property("buffer never contains the focus") = forAll(streamAndPathsGen, nonZeroBufferSizeGen(16)) {
    (sp: StreamAndPaths[Id, Int], size: BufferSize) => forAll(sp.pathGen) { path: Path =>
      implicit val params: Test.Parameters = Parameters.default.withMinSize(10)
      BufferedZipper(sp.stream, size.max)
        .fold[List[Boolean]](List())(in => unzipAndMapViaPath[Id, Int, Boolean](in, bs => bufferContains(bs, bs.focus), path.steps))
        .forall(_ == false) }
  }

  property("buffer limit is never exceeded") = forAll(streamAndPathsGen, nonZeroBufferSizeGen(16)) {
    implicit val params: Test.Parameters = Parameters.default.withMinSize(10)
    (sp: StreamAndPaths[Id, Int], size: BufferSize) => forAll(sp.pathGen) { path: Path =>
      BufferedZipper[Id, Int](sp.stream, size.max)
        .fold[List[Long]](List())(unzipAndMapViaPath[Id, Int, Long](_, measureBufferContents[Id, Int], path.steps))
        .forall(_ <= size.max.get) }
  }

  // TODO replace var with state monad
  property("effect only takes place when focus called with a stream of one element regardless of buffer size") = forAll {
    (elem: Short, size: BufferSize) => {
      var outsideState: Long = 0
      val instructions = Stream(elem).map(i => IO{ outsideState += i; outsideState })
      val io = for {
        mBuff <- BufferedZipper[IO, Long](instructions, size.max)
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