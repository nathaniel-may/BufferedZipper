package util

//scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.{Properties, Gen}, Gen.choose
import scalaz.Scalaz.Id

//Scala
import Stream.Empty

//project



object BufferedZipperProperties extends Properties("shuffle") {

  def unzipToList[T](in: Option[BufferedZipper[T]]): List[T] = {
    def go(z: Option[BufferedZipper[T]], l: List[T]): List[T] =
      go(z.flatMap(_.next), z.flatMap(_.focus).fold(l)(_ :: l))

    go(in, List()).reverse
  }

  def unzipToListWithBufferSize[T](in: Option[BufferedZipper[T]]): List[(T, Long)] = {
    def go(z: Option[BufferedZipper[T]], l: List[(T, Long)]): List[(T, Long)] =
      go(
        z.flatMap(_.next),
        z.flatMap(_.focus)
          .fold(l)(zip => (zip, BufferedZipper.measureBuffer(z.get)) :: l)) //TODO get

    go(in, List()).reverse
  }

  property("list of unzipped elements is the same as the input with no buffer limit") = forAll {
    inStream: Stream[Int] => unzipToList(Some(BufferedZipper(inStream))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a small buffer limit") = forAll {
    inStream: Stream[Int] => unzipToList(Some(BufferedZipper(inStream, Some(100L)))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input with a buffer limit of 0") = forAll {
    inStream: Stream[Int] => unzipToList(Some(BufferedZipper(inStream, Some(0)))) == inStream.toList
  }

  property("list of unzipped elements is the same as the input regardless of buffer limit") = forAll {
    (inStream: Stream[Int], max: Option[Long]) =>
      unzipToList(Some(BufferedZipper(inStream, max))) == inStream.toList
  }

  property("buffer limit is never exceeded") = forAll {
    (inStream: Stream[Int], max: Long) =>
      unzipToListWithBufferSize(Some(BufferedZipper(inStream, Some(max))))
        .forall(_._2 <= max)
  }

}
