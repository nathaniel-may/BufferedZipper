package util

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties

// Scala
import scalaz.Scalaz.Id
import scalaz.effect.IO

// Project
import testingUtil.BufferedZipperFunctions._
import testingUtil.Arbitrarily.{StreamAtLeast2, UniqueStreamAtLeast1, Path, BufferSize, LimitedBufferSize, NonZeroBufferSize}
import testingUtil.Arbitrarily.{aStreamAtLeast2, aUniqueStreamAtLeast1, aPath, aBufferSize, aLimitedBufferSize, aNonZeroBufferSize}

//TODO add test for buffer eviction in the correct direction ....idk how.
object BufferedZipperProperties extends Properties("BufferedZipper") {

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

  property("next then prev should result in the first element regardless of buffer limit") = forAll {
    (inStream: StreamAtLeast2[Int], max: Option[Long]) => (for {
      zipper <- BufferedZipper[Id, Int](inStream.wrapped, max)
      next   <- zipper.next
      prev   <- next.prev
    } yield prev.focus) == inStream.wrapped.headOption
  }

  property("list of elements unzipping from the back is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      BufferedZipper[Id, Int](inStream, max)
        .fold(inStream.isEmpty)(toList(Backwards, _) == inStream.toList)
  }

  property("buffer limit is never exceeded when traversed once linearlly") = forAll {
    (inStream: Stream[Int], size: LimitedBufferSize) =>
      BufferedZipper[Id, Int](inStream, Some(size.max))
        .fold[List[Long]](List())(unzipToListOfBufferSize(Forwards, _))
        .forall(_ <= size.max)
  }

  property("buffer is being used for streams of at least two elements when traversed once linearly") = forAll {
    (inStream: StreamAtLeast2[Int], size: NonZeroBufferSize) =>
      BufferedZipper[Id, Int](inStream.wrapped, size.max)
        .fold[List[Long]](List())(unzipToListOfBufferSize(Forwards, _))
        .tail.forall(_ > 0)
  }

  property("buffer is not being used for streams of one or less elements when traversed once forwards") = forAll {
    (in: Option[Int], size: NonZeroBufferSize) =>
      BufferedZipper[Id, Int](in.fold[Stream[Int]](Stream())(Stream(_)), size.max)
        .fold[List[Long]](List())(unzipToListOfBufferSize(Forwards, _))
        .forall(_ == 0)
  }

  property("buffer is not being used for streams of one or less elements when traversed backwards") = forAll {
    (in: Option[Int], size: NonZeroBufferSize) =>
      BufferedZipper[Id, Int](in.fold[Stream[Int]](Stream())(Stream(_)), size.max)
        .fold[List[Long]](List())(unzipToListOfBufferSize(Backwards, _))
        .forall(_ == 0)
  }

  property("buffer never contains the focus when traversed once forwards") = forAll {
    (inStream: UniqueStreamAtLeast1[Int], size: NonZeroBufferSize) =>
      BufferedZipper(inStream.wrapped, size.max)
        .fold[List[Boolean]](List())(in => unzipAndMap[Id, Int, Boolean](Forwards, in, bs => bufferContains(bs, bs.focus)))
        .forall(_ == false)
  }

  property("buffer never contains the focus when traversed backwards") = forAll {
    (inStream: UniqueStreamAtLeast1[Int], size: NonZeroBufferSize) =>
      BufferedZipper(inStream.wrapped, size.max)
        .fold[List[Boolean]](List())(in => unzipAndMap[Id, Int, Boolean](Backwards, in, bs => bufferContains(bs, bs.focus)))
        .forall(_ == false)
  }

  property("buffer limit is never exceeded on a random path") = forAll {
    (inStream: Stream[Int], path: Path, size: LimitedBufferSize) =>
      BufferedZipper[Id, Int](inStream, Some(size.max))
        .fold[List[Long]](List())(unzipAndMapViaPath[Id, Int, Long](_, bs => measureBufferContents(bs), path.wrapped))
        .forall(_ <= size.max)
  }

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