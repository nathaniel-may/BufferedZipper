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

object BufferedZipperProperties extends Properties("BufferedZipper") {
  val meter = new MemoryMeter

  case class StreamAtLeast2[T](wrapped: Stream[T])
  case class PositiveLong(wrapped: Long)

  implicit val aStreamAtLeast2: Arbitrary[StreamAtLeast2[Int]] = Arbitrary((for {
    l1 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
    l2 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
  } yield l1 ::: l2).map(l => StreamAtLeast2(l.toStream)))

  implicit val aPositiveLong: Arbitrary[PositiveLong] =
    Arbitrary(Gen.choose(0, Long.MaxValue).map(PositiveLong))

  def traverseToList[M[_] : Monad, T](in: Option[M[BufferedZipper[M, T]]]): M[List[T]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    // TODO make stack-safe
    def go(z: Option[M[BufferedZipper[M, T]]], l: M[List[T]]): M[List[T]] = z match {
      case Some(mbz) => mbz.map { bz => go(bz.next, l.map(bz.focus :: _)) }.flatMap(x => x) // TODO, no flatten???
      case None => l
    }

    go(in, point(List())).map(_.reverse)
  }

  def goToEnd[M[_]: Monad, A](bz: BufferedZipper[M,A]): M[BufferedZipper[M,A]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    bz.next match {
      case None      => point(bz)
      case Some(mbz) => mbz.flatMap(goToEnd(_))
    }
  }

  def traverseFromBackToList[M[_]: Monad, T](in: BufferedZipper[M, T]): M[List[T]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, T], l: M[List[T]]): M[List[T]] = z.prev match {
      case Some(mPrev) => mPrev.flatMap(prev => go(prev, l.map(z.focus :: _)))
      case None        => l.map(z.focus :: _)
    }

    // stops right before it returns None
    def goToEnd(z: BufferedZipper[M, T]): M[BufferedZipper[M, T]] =
      z.next.fold(point(z))(_.flatMap(goToEnd))

    goToEnd(in).flatMap(x => go(x, point(List())))
  }

  def unzipToListWithBufferSize[M[_]: Monad, T](in: Option[M[BufferedZipper[M, T]]]): M[List[(T, Long)]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: Option[M[BufferedZipper[M, T]]], l: M[List[(T, Long)]]): M[List[(T, Long)]] =
      z.fold(l)(mbz => mbz.flatMap { bz =>
        go(bz.next, l.map { lt => (bz.focus, measureBufferContents(bz)) :: lt } ) } )

    go(in, point(List())).map(_.reverse)
  }

  private[util] def measureBufferContents[M[_]: Monad, T](bs: BufferedZipper[M, T]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)

  property("list of unzipped elements is the same as the input with no buffer limit") = forAll {
    inStream: Stream[Int] => traverseToList(BufferedZipper[Id, Int](inStream, None)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a small buffer limit") = forAll {
    inStream: Stream[Int] => traverseToList(BufferedZipper[Id, Int](inStream, Some(100L))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a buffer limit of 0") = forAll {
    inStream: Stream[Int] => traverseToList(BufferedZipper[Id, Int](inStream, Some(0L))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      traverseToList(BufferedZipper[Id, Int](inStream, max)) == inStream.toList
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
      BufferedZipper[Id, Int](inStream, max).fold(inStream.isEmpty)(traverseFromBackToList(_) == inStream.toList)
  }

  property("buffer limit is never exceeded when traversed once linearlly") = forAll {
    (inStream: Stream[Int], max: PositiveLong) =>
      unzipToListWithBufferSize(BufferedZipper[Id, Int](inStream, Some(max.wrapped)))
        .forall(_._2 <= max.wrapped)
  }

  property("buffer is being used when traversed once linearlly") = forAll {
    (inStream: Stream[Int], max: PositiveLong) =>
      unzipToListWithBufferSize(BufferedZipper[Id, Int](inStream, Some(max.wrapped + 16)))
        .forall(_._2 > 0)
  }

  // TODO make a better path. Maybe def makes it half way in? how to make a Gen[Path] that has a decent likelihood of touching all elements in the stream?
  // TOOD make stack-safe
  property("buffer limit is never exceeded on a random path") = forAll {
    (inStream: Stream[Int], path: Stream[Boolean], max: PositiveLong) => {
      def go[M[_] : Monad, T](zipper: Option[M[BufferedZipper[M, T]]], path: Stream[Boolean], l: M[List[Long]]): M[List[Long]] = {
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