package util

//scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Properties}
import scalaz.Scalaz.Id
import Stream.Empty

object BufferedZipperProperties extends Properties("BufferedZipper") {

  case class StreamAtLeast2[T](wrapped: Stream[T])
  case class PositiveLong(wrapped: Long)

  implicit val aStreamAtLeast2: Arbitrary[StreamAtLeast2[Int]] = Arbitrary((for {
    l1 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
    l2 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
  } yield l1 ::: l2).map(l => StreamAtLeast2(l.toStream)))

  implicit val aPositiveLong: Arbitrary[PositiveLong] =
    Arbitrary(Gen.choose(0, Long.MaxValue).map(PositiveLong))

  def traverseToList[T](in: Option[BufferedZipper[T]]): List[T] = {
    def go(z: Option[BufferedZipper[T]], l: List[T]): List[T] = z match {
      case Some(bz) => go(bz.next, bz.focus :: l)
      case None     => l
    }

    go(in, List()).reverse
  }

  def traverseFromBackToList[T](in: Option[BufferedZipper[T]]): List[T] = {
    def go(z: Option[BufferedZipper[T]], l: List[T]): List[T] = z match {
      case Some(bz) => go(bz.prev, bz.focus :: l)
      case None     => l
    }

    // stops right before it returns None
    def goToEnd(z: Option[BufferedZipper[T]]): Option[BufferedZipper[T]] =
      z.map(_.next.fold(z)(n => goToEnd(Some(n)))).getOrElse(z)

    go(goToEnd(in), List())
  }

  def unzipToListWithBufferSize[T](in: Option[BufferedZipper[T]]): List[(T, Long)] = {
    def go(z: Option[BufferedZipper[T]], l: List[(T, Long)]): List[(T, Long)] =
      z.fold(l)(bz => go(
        bz.next,
        (bz.focus, BufferedZipper.measureBufferContents(bz)) :: l))

    go(in, List()).reverse
  }

  property("list of unzipped elements is the same as the input with no buffer limit") = forAll {
    inStream: Stream[Int] => traverseToList(BufferedZipper(inStream)) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a small buffer limit") = forAll {
    inStream: Stream[Int] => traverseToList(BufferedZipper(inStream, Some(100L))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a buffer limit of 0") = forAll {
    inStream: Stream[Int] => traverseToList(BufferedZipper(inStream, Some(0))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      traverseToList(BufferedZipper(inStream, max)) == inStream.toList
  }

  property("next then prev should result in the first element regardless of buffer limit") = forAll {
    (inStream: StreamAtLeast2[Int], max: Option[Long]) => (for {
      zipper <- BufferedZipper(inStream.wrapped, max)
      next   <- zipper.next
      prev   <- next.prev
    } yield prev.focus) == inStream.wrapped.headOption
  }

  property("list of elements unzipping from the back is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      traverseFromBackToList(BufferedZipper(inStream, max)) == inStream.toList
  }

  property("buffer limit is never exceeded when traversed once linearlly") = forAll {
    (inStream: Stream[Int], max: PositiveLong) =>
      unzipToListWithBufferSize(BufferedZipper(inStream, Some(max.wrapped)))
        .forall(_._2 <= max.wrapped)
  }

  // TODO make a better path. Maybe def makes it half way in? how to make a Gen[Path]
  // TODO that has a decent likelihood of touching all elements in the stream?
  property("buffer limit is never exceeded on a random path") = forAll {
    (inStream: Stream[Int], path: Stream[Boolean], max: PositiveLong) => {
      def go[T](zipper: Option[BufferedZipper[T]], path: Stream[Boolean], l: List[Long]): List[Long] = (zipper, path) match {
        case (Some(z), next #:: p) =>
          if(next) go(z.next, p, BufferedZipper.measureBufferContents(z) :: l)
          else     go(z.prev, p, BufferedZipper.measureBufferContents(z) :: l)
        case (_,       Empty)      => l
        case (None,    _)          => l
      }


      go(BufferedZipper(inStream, Some(max.wrapped)), path, List())
        .forall(_ <= max.wrapped)
    }
  }

}
