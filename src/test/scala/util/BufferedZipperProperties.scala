package util

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Properties}, Arbitrary.{arbLong, arbOption}
import testingUtil.Arbitrarily.boundedStreamAndPathsGen

// Scala
import scalaz.Monad
import scalaz.Scalaz.Id
import scalaz.effect.IO

// Project
import testingUtil.BufferedZipperFunctions._
import testingUtil.Arbitrarily.{StreamAndPaths, Path, BufferSize, LimitedBufferSize, NonZeroBufferSize}
import testingUtil.Arbitrarily.{anIdIntStreamAndPaths, aBufferSize, aLimitedBufferSize, aNonZeroBufferSize}

//TODO add test for buffer eviction in the correct direction ....idk how.
object BufferedZipperProperties extends Properties("BufferedZipper") {

//  case class StreamAndPathsAtLeast2[M[_]: Monad, A](stream: Stream[M[A]], pathGen: Gen[Path])
//  case class UniqueStreamAndPathsAtLeast1[M[_]: Monad, A](stream: Stream[M[A]], pathGen: Gen[Path])

//  implicit val aIdIntStreamAtLeast2: Arbitrary[StreamAndPathsAtLeast2[Id, Int]] =
//    Arbitrary(boundedStreamAndPathsGen(2, Int.MaxValue).map(x => UniqueStreamAndPathsAtLeast1(x.stream, x.pathGen)))
//
//  implicit val aIdIntStreamAtLeast2: Arbitrary[UniqueStreamAndPathsAtLeast1] =
//    Arbitrary(boundedUniqueStreamAndPathsGen(1, Int.MaxValue).map(x => UniqueStreamAndPathsAtLeast1(x.stream, x.pathGen)))

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
    forAll(boundedStreamAndPathsGen(2, Int.MaxValue), arbOption[Long](arbLong).arbitrary) {
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

  //TODO pathify
  property("buffer limit is never exceeded when traversed once linearlly") = forAll {
    (inStream: Stream[Int], size: LimitedBufferSize) =>
      BufferedZipper[Id, Int](inStream, Some(size.max))
        .fold[List[Long]](List())(unzipToListOfBufferSize(Forwards, _))
        .forall(_ <= size.max)
  }

  //TODO THIS IS THE ONE I WAS WORKING ON
  property("buffer is being used for streams of at least two elements") =
    forAll(boundedStreamAndPathsGen(2, Int.MaxValue), aNonZeroBufferSize.arbitrary) {
      (sp: StreamAndPaths[Id, Int], size: NonZeroBufferSize) => forAll(sp.pathGen) { path: Path =>
        println(s"STREAM: ${sp.stream.toList}")
        println(s"PATH  : ${path.steps.toList}")
        BufferedZipper[Id, Int](sp.stream, size.max)
          .fold[List[Long]](List())(bz => unzipAndMapViaPath(bz, measureBufferContents[Id, Int], path.steps))
          .tail.forall(_ > 0) }
    }

  //TODO pathify
  property("buffer is not being used for streams of one or less elements when traversed once forwards") = forAll {
    (in: Option[Int], size: NonZeroBufferSize) =>
      BufferedZipper[Id, Int](in.fold[Stream[Int]](Stream())(Stream(_)), size.max)
        .fold[List[Long]](List())(unzipToListOfBufferSize(Forwards, _))
        .forall(_ == 0)
  }

  //TODO see above^^
  property("buffer is not being used for streams of one or less elements when traversed backwards") = forAll {
    (in: Option[Int], size: NonZeroBufferSize) =>
      BufferedZipper[Id, Int](in.fold[Stream[Int]](Stream())(Stream(_)), size.max)
        .fold[List[Long]](List())(unzipToListOfBufferSize(Backwards, _))
        .forall(_ == 0)
  }

  property("buffer never contains the focus") = forAll {
    (sp: StreamAndPaths[Id, Int], size: NonZeroBufferSize) => forAll(sp.pathGen) { path: Path =>
      BufferedZipper(sp.stream, size.max)
        .fold[List[Boolean]](List())(in => unzipAndMapViaPath[Id, Int, Boolean](in, bs => bufferContains(bs, bs.focus), path.steps))
        .forall(_ == false) }
  }

  property("buffer limit is never exceeded") = forAll {
    (sp: StreamAndPaths[Id, Int], size: LimitedBufferSize) => forAll(sp.pathGen) { path: Path =>
      BufferedZipper[Id, Int](sp.stream, Some(size.max))
        .fold[List[Long]](List())(unzipAndMapViaPath[Id, Int, Long](_, bs => measureBufferContents(bs), path.steps))
        .forall(_ <= size.max) }
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