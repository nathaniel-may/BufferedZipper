package zipper

// Scalacheck
import org.scalacheck.Prop.{forAll, forAllNoShrink}
import org.scalacheck.{Arbitrary, Properties, Shrink}
import util.Generators._
import util.BufferTypes._
import util.Shrinkers

// Scala
import scalaz.Scalaz.Id

// Project
import util.BufferedZipperFunctions._
import util.PropertyHelpers._
import util.Directions.{N, P}

//TODO separate test file for WindowBuffer
//TODO add test that buffer size should only increase for ints
//TODO add tests for dealing with non-uniform types like strings. What if the first string is larger than the buffer size?
//     -  TODO what if one entry maxes out the buffer size, and the next in focus is smaller than the minimum?
//TODO arch - should test inputs be streams or buffered zippers?
//TODO test that the estimated buffersize (for capped buffers) is accurate ...or at least never goes negative.
object BufferedZipperProperties extends Properties("BufferedZipper") {

  implicit val arbPath: Arbitrary[Path] = Arbitrary(pathGen)
  implicit val shrinkUniqueStream: Shrink[UniqueStream[Int]] = Shrinkers.shrinkUniqueStream[Int]


  property("toStream is the same as the streamInput regardless of starting point") = forAll {
    (inStream: Stream[Int], path: Path) => BufferedZipper[Id, Int](inStream, None)
      .fold[Stream[Int]](Stream()) { move[Id, Int](path, _).toStream } == inStream
  }

  //TODO start with generated BufferedZipper instead of a Stream?
  property("toStream uses buffer to minimize effectful calls") = forAll {
    (inStream: Stream[Int], path: Path) =>
      val start = BufferedZipper[Counter, Int](inStream.map(bumpCounter), None)
        .map { _.flatMap { bz => move(path, bz).flatMap(zeroCounter) } }
      val effects = start.fold(0) { _.flatMap(_.toStream).exec(0) }
      val shouldBe = start.fold(0) { _.map { bz =>
        inStream.size - bz.buffer.size }.eval(0) }
      effects == shouldBe
  }

  property("map uses buffer to minimize effectful calls") = forAll {
    (inStream: Stream[Int], path: Path) =>
      val start = BufferedZipper[Counter, Int](inStream.map(bumpCounter), None)
        .map { _.flatMap { bz => move(path, bz).flatMap(zeroCounter) } }
      val effects = start.fold(0) { _.flatMap(_.map(_ + 1).toStream).exec(0) }
      val shouldBe = start.fold(0) { _.map { bz =>
        inStream.size - bz.buffer.size }.eval(0) }
      effects == shouldBe
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
      (s: Stream[Int], size: FlexibleBuffer) =>
        BufferedZipper[Id, Int](s, Some(size.cap))
          .fold(s.isEmpty)(toList(Backwards, _) == s.toList)
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
          .fold(s.isEmpty) { bz => assertOnPath[Id, Int](
            bz,
            path,
            measureBufferContents[Id, Int](_) <= size.cap) }
    }

  property("buffer is being used for streams of at least two elements") =
    forAllNoShrink(streamGenMin[Id, Int](2), nonZeroBufferSizeGen(16), pathGen) {
      (s: Stream[Int], size: BufferSize, path: Path) =>
        BufferedZipper[Id, Int](s, Some(size.cap)).fold[List[Long]](List())(bz => {
          resultsOnPath[Id, Int, Long](
            bz,
            path,
            measureBufferContents[Id, Int]).drop(1)
        }).forall(_ > 0)
    }

  property("buffer is not being used for streams of one or less elements") =
    forAll(implicitly[Arbitrary[Option[Int]]].arbitrary, nonZeroBufferSizeGen(16), pathGen) {
      (oi: Option[Int], size: LargerBuffer, path: Path) =>
        BufferedZipper[Id, Int](oi.fold[Stream[Int]](Stream())(Stream(_)), Some(size.cap))
          .fold(oi.isEmpty) { bz => assertOnPath[Id, Int](bz, path, measureBufferContents[Id, Int](_) == 0) }
    }

  property("buffer never has duplicate items") =
    forAll(uniqueIntStreamGen, nonZeroBufferSizeGen(16), pathGen) {
      (us: UniqueStream[Int], size: LargerBuffer, path: Path) =>
        BufferedZipper(us.s, Some(size.cap))
          .fold(us.s.isEmpty) { in => assertOnPath[Id, Int](
            in,
            path,
            bs => bs.buffer.toList.groupBy(identity).valuesIterator.forall(_.size == 1)) }
    }

  property("buffer is always a segment of the input") =
      forAll(uniqueIntStreamGen, nonZeroBufferSizeGen(16), pathGen) {
        (us: UniqueStream[Int], size: LargerBuffer, path: Path) =>
          BufferedZipper(us.s, Some(size.cap))
            .fold(us.s.isEmpty) { in => assertOnPath[Id, Int](
              in,
              path,
              bs => us.s.containsSlice(bs.buffer.toList)) }
      }

  property("buffer never contains the focus") =
    forAll(uniqueIntStreamGen, nonZeroBufferSizeGen(16), pathGen) {
      (us: UniqueStream[Int], size: LargerBuffer, path: Path) =>
        BufferedZipper(us.s, Some(size.cap))
          .fold(us.s.isEmpty) { in => assertOnPath[Id, Int](
            in,
            path,
            bs => !bs.buffer.contains(bs.focus)) }
    }

  property("buffer limit is never exceeded") =
    forAll(intStreamGen, nonZeroBufferSizeGen(16), pathGen) {
      (s: Stream[Int], size: LargerBuffer, path: Path) =>
        BufferedZipper[Id, Int](s, Some(size.cap))
          .fold(s.isEmpty) { assertOnPath[Id, Int](_, path, measureBufferContents[Id, Int](_) <= size.cap) }
    }

  property ("buffer evicts the correct elements") =
    forAll(intStreamGen, nonZeroBufferSizeGen(16), pathGen) {
      (s: Stream[Int], size: LargerBuffer, path: Path) =>
        val (lrs, realPath) = BufferedZipper[Id, Int](s, Some(size.cap))
          .fold[(List[(Vector[Int], Vector[Int])], Path)]((List(), Stream())) {
            resultsAndPathTaken[Id, Int, (Vector[Int], Vector[Int])](_, path, bz => (bz.buffer.lefts, bz.buffer.rights)) }
        lrs.zip(lrs.drop(1))
          .zip(realPath)
          .map { case (((l0, r0), (l1, r1)), np) => np match {
            case N => l1.size >= l0.size && r1.size <= r0.size
            case P => r1.size >= r0.size && l1.size <= l0.size
          } }
          .forall(_ == true)
    }

  property("effect only takes place once with a stream of one element regardless of buffer size") =
    forAll(implicitly[Arbitrary[Short]].arbitrary, flexibleBufferSizeGen) {
      (elem: Short, size: FlexibleBuffer) =>
        BufferedZipper[Counter, Short](Stream(elem).map(bumpCounter), Some(size.cap))
          .fold(false) { _.exec(0) == 1 }
    }

  property("with unlimited buffer, effect happens at most once per element") = forAll {
    (inStream: Stream[Int], path: Path) => {
      BufferedZipper[Counter, Int](inStream.map(bumpCounter), None)
        .map { _.flatMap(move(path, _)) }
        .fold(0){ _.exec(0) } <= inStream.size
    }
  }

}