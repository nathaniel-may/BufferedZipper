package util

//scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.{Properties, Gen}, Gen.choose
import scalaz.Scalaz.Id

//Scala
import Stream.Empty

//project



object BufferedZipperProperties extends Properties("shuffle") {

  def traverseToList[T](in: Option[BufferedZipper[T]]): List[T] = {
    def go(z: Option[BufferedZipper[T]], l: List[T]): List[T] =
      z.fold(l)(bz => go(bz.next, z.map(_.focus).fold(l)(_ :: l)))

    go(in, List()).reverse
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

  property("buffer limit is never exceeded") = forAll {
    (inStream: Stream[Int], max: Long) =>
      unzipToListWithBufferSize(BufferedZipper(inStream, Some(max)))
        .forall {case (_, size) => size <= max || (max < 0 && size == 0) }
  }

}
