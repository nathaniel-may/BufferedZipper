package util

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Properties}

// Scala
import Stream.Empty
import scalaz.Monad
import scalaz.Scalaz.Id
import scalaz.effect.IO
import org.github.jamm.MemoryMeter

//TODO make positiveLong a "meaningfulbuffer" so it's at least 16 units long
//TODO add a test to make sure the focus is not in the buffer (unique input, add contains helper function here.)
//TODO add test for buffer eviction in the correct direction
object BufferedZipperProperties extends Properties("BufferedZipper") {
  val meter = new MemoryMeter

  case class StreamAtLeast2[A](wrapped: Stream[A])
  case class PositiveLong(wrapped: Long)
  case class UniqueStreamAtLeast1[A](wrapped: Stream[A])

  implicit val aUniqueStreamAtLeast1: Arbitrary[UniqueStreamAtLeast1[Int]] =
    Arbitrary(Gen.atLeastOne(-20 to 20).map(seq => UniqueStreamAtLeast1(seq.toStream)))

  implicit val aStreamAtLeast2: Arbitrary[StreamAtLeast2[Int]] = Arbitrary((for {
    l1 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
    l2 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
  } yield l1 ::: l2).map(l => StreamAtLeast2(l.toStream)))

  implicit val aPositiveLong: Arbitrary[PositiveLong] =
    Arbitrary(Gen.choose(0, Long.MaxValue).map(PositiveLong))

  //TODO add to type?
  def toList[M[_] : Monad, A](in: BufferedZipper[M, A]): M[List[A]] =
    unzipAndMap[M, A, A](in, bs => bs.focus)

  def goToEnd[M[_]: Monad, A](bz: BufferedZipper[M,A]): M[BufferedZipper[M,A]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    bz.next match {
      case None      => point(bz)
      case Some(mbz) => mbz.flatMap(goToEnd(_))
    }
  }

  def traverseFromBackToList[M[_]: Monad, A](in: BufferedZipper[M, A]): M[List[A]] =
    unzipAndMapBackwards[M, A, A](in, bs => bs.focus)

  def unzipToListOfBufferSize[M[_]: Monad, A](in: BufferedZipper[M, A]): M[List[Long]] =
    unzipAndMap[M, A, Long](in, bs => measureBufferContents(bs))

  def unzipAndMap[M[_] : Monad, A, B](in: BufferedZipper[M, A], f:BufferedZipper[M, A] => B) : M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, A], l: M[List[B]]): M[List[B]] =
      z.next.fold(l.map(f(z) :: _))(mbz => mbz.flatMap(zNext =>
        go(zNext, l.map(f(z) :: _))))

    go(in, point(List())).map(_.reverse)
  }

  def unzipAndMapBackwards[M[_]: Monad, A, B](in: BufferedZipper[M, A], f: BufferedZipper[M, A] => B): M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, A], l: M[List[B]]): M[List[B]] = z.prev match {
      case Some(mPrev) => mPrev.flatMap(prev => go(prev, l.map(f(z) :: _)))
      case None        => l.map(f(z) :: _)
    }

    // stops right before it returns None
    def goToEnd(z: BufferedZipper[M, A]): M[BufferedZipper[M, A]] =
      z.next.fold(point(z))(_.flatMap(goToEnd))

    goToEnd(in).flatMap(x => go(x, point(List())))
  }

  def bufferContains[M[_]: Monad, A](bs: BufferedZipper[M, A], elem: A): Boolean =
    bs.buffer.v.contains(Some(elem))

  def measureBufferContents[M[_]: Monad, A](bs: BufferedZipper[M, A]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)

  property("list of unzipped elements is the same as the input with no buffer limit") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, None)
      .fold[List[Int]](List())(toList(_)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a small buffer limit") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, Some(100L))
      .fold[List[Int]](List())(toList(_)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a buffer limit of 0") = forAll {
    inStream: Stream[Int] => BufferedZipper[Id, Int](inStream, Some(0L))
      .fold[List[Int]](List())(toList(_)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      BufferedZipper[Id, Int](inStream, max).fold[List[Int]](List())(toList(_)) == inStream.toList
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
        .fold(inStream.isEmpty)(traverseFromBackToList(_) == inStream.toList)
  }

  property("buffer limit is never exceeded when traversed once linearlly") = forAll {
    (inStream: Stream[Int], max: PositiveLong) =>
      BufferedZipper[Id, Int](inStream, Some(max.wrapped))
        .fold[List[Long]](List())(unzipToListOfBufferSize(_))
        .forall(_ <= max.wrapped)
  }

  property("buffer is being used for streams of at least two elements when traversed once linearly") = forAll {
    (inStream: StreamAtLeast2[Int], max: PositiveLong) =>
      BufferedZipper[Id, Int](inStream.wrapped, Some(max.wrapped + 16))
        .fold[List[Long]](List())(unzipToListOfBufferSize(_))
        .tail.forall(_ > 0)
  }

  //TODO use a function that goes backwards too
  property("buffer is not being used for streams of one or less elements when traversed once linearly") = forAll {
    (in: Option[Int], max: PositiveLong) =>
      BufferedZipper[Id, Int](in.fold[Stream[Int]](Stream())(Stream(_)), Some(max.wrapped + 16))
        .fold[List[Long]](List())(unzipToListOfBufferSize(_))
        .forall(_ == 0)
  }

  //TODO use a function that goes backwards too.
  property("buffer never contains the focus") = forAll {
    (inStream: UniqueStreamAtLeast1[Int], max: PositiveLong) =>
      BufferedZipper(inStream.wrapped, Some(max.wrapped + 16))
        .fold[List[Boolean]](List())(in => unzipAndMap[Id, Int, Boolean](in, bs => bufferContains(bs, bs.focus)))
        .forall(_ == false)
  }

  // TODO make a better path. Maybe def makes it half way in? how to make a Gen[Path] that has a decent likelihood of touching all elements in the stream?
  // TODO make stack-safe?
  property("buffer limit is never exceeded on a random path") = forAll {
    (inStream: Stream[Int], path: Stream[Boolean], max: PositiveLong) => {
      def go[M[_] : Monad, A](zipper: Option[M[BufferedZipper[M, A]]], path: Stream[Boolean], l: M[List[Long]]): M[List[Long]] = {
        val monadSyntax = implicitly[Monad[M]].monadSyntax
        import monadSyntax._

        (zipper, path) match {
          case (Some(z), next #:: p) =>
            if (next) z.flatMap(bz => go(bz.next, p, l.map(list => measureBufferContents(bz) :: list)))
            else      z.flatMap(bz => go(bz.prev, p, l.map(list => measureBufferContents(bz) :: list)))
          case (_, Empty) => l
          case (None, _) => l
        }
      }


      go(BufferedZipper[Id, Int](inStream, Some(max.wrapped)), path, implicitly[Monad[Id]].point(List[Long]()))
        .forall(_ <= max.wrapped)
    }
  }

  property("effect only takes place when focus called with a stream of one element regardless of buffer size") = forAll {
    (elem: Short, max: PositiveLong) => {
      var outsideState: Long = 0
      val instructions = Stream(elem).map(i => IO{ outsideState += i; outsideState })
      val io = for {
        mBuff <- BufferedZipper[IO, Long](instructions, Some(max.wrapped))
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
      val sameContents = BufferedZipper[IO, Int](inStreamWithEffect, None).fold(inStream.isEmpty)(_.flatMap(traverseFromBackToList(_)).unsafePerformIO() == inStream.toList)
      //println(s"inStream: ${inStream.toList}, sameContents: $sameContents, effectCount: $outsideState")
      sameContents && outsideState == inStream.size
    }
  }

}