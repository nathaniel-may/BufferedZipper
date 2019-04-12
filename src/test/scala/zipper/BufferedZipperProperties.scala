package zipper

// Scalacheck
import org.scalacheck.Prop.{forAll, forAllNoShrink}
import org.scalacheck.{Arbitrary, Properties}
import util.Generators._

// Scala
import scalaz.Scalaz.Id

// Project
import util.PropertyFunctions._
import util.Directions.{N, P}

//TODO separate test file for WindowBuffer
//TODO add tests for dealing with non-uniform types like strings. What if the first string is larger than the buffer size?
//     -  TODO what if one entry maxes out the buffer size, and the next in focus is smaller than the minimum?
//TODO test that the estimated buffersize (for capped buffers) is accurate ...or at least never goes negative.
object BufferedZipperProperties extends Properties("BufferedZipper") {
  val noEffect = WithEffect[Id]()
  import noEffect.{bZipGen, bZipGenMax, bZipGenMin, uniqueBZipGen} // allows for these functions to be called without explicit Id effect

  implicit val aPath: Arbitrary[Path] = Arbitrary(pathGen)
  implicit val aBufferSize: Arbitrary[Limit] = Arbitrary(bufferSizeGen)

  property("toStream is the same as the streamInput regardless of starting point and buffer size") = forAll {
    (inStream: Stream[Int], limits: Limit, path: Path) => BufferedZipper[Int](inStream, limits)
      .fold[Stream[Int]](Stream()) { move[Id, Int](path, _).toStream } == inStream
  }

  property("toStream uses buffer to minimize effectful calls") =
    forAll(WithEffect[Counter].bZipGen[Int](bufferSizeGen, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, Int]], path: Path) =>
        val start = cbz.flatMap { bz => move(path, bz).flatMap(zeroCounter) }
        val effects = start.flatMap(_.toStream).exec(0)
        val shouldBe = start.map { bz =>
          bz.toStream.eval(0).size - bz.buffer.size }.eval(0)
        effects == shouldBe
    }

  property("map uses buffer to minimize effectful calls") =
    forAll(WithEffect[Counter].bZipGen[Int](bufferSizeGen, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, Int]], path: Path) =>
        val start = cbz.flatMap { bz => move(path, bz).flatMap(zeroCounter) }
        val effects = start.flatMap(_.map(_ + 1).toStream).exec(0)
        val shouldBe = start.map { bz =>
          bz.toStream.eval(0).size - bz.buffer.size }.eval(0)
        effects == shouldBe
    }

  property("next then prev should result in the first element regardless of buffer limit") =
    forAll(bZipGenMin[Int](2, bufferSizeGen)) {
      (zipper: BufferedZipper[Id, Int]) => (for {
        next   <- zipper.next
        prev   <- next.prev
      } yield prev.focus) == zipper.toStream.headOption
    }

  // TODO measureBufferContents is exponential
  property("buffer limit is never exceeded") =
    forAll(bZipGen[Int](bufferGenAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](bz, path, bzz => bzz.buffer.limit match {
          case Bytes(max) => measureBufferContents(bzz.buffer) <= max
          case _          => false
        })
    }

  property("buffer is being used when there are at least two elements and space for at least one element") =
    forAllNoShrink(bZipGenMin[Int](2, bufferGenAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
          resultsOnPath[Id, Int, Long](bz, path, bzz => measureBufferContents(bzz.buffer))
            .drop(1)
            .forall(_ > 0)
    }

  property("buffer is not being used for streams of one or less elements") =
    forAll(bZipGenMax[Int](1, bufferGenAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](bz, path, bzz => measureBufferContents(bzz.buffer) == 0)
    }

  property("buffer never has duplicate items") =
    forAll(uniqueBZipGen[Int](bufferGenAtLeast(16)), pathGen) {
      (in: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](in, path, bz =>
          bz.buffer.toList.groupBy(identity).valuesIterator.forall(_.size == 1))
    }

  property("buffer is always a segment of the input") =
      forAll(uniqueBZipGen[Int](bufferGenAtLeast(16)), pathGen) {
        (in: BufferedZipper[Id, Int], path: Path) =>
          assertOnPath[Id, Int](in, path, bz =>
            in.toStream.containsSlice(bz.buffer.toList))
      }

  property("buffer never contains the focus") =
    forAll(uniqueBZipGen[Int](bufferGenAtLeast(16)), pathGen) {
      (in: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](in, path, bz => !bz.buffer.contains(bz.focus))
    }

  property("buffer limit is never exceeded") =
    forAll(bZipGen[Int](bufferGenAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](bz, path, bzz => bzz.buffer.limit match {
          case Bytes(max) => measureBufferContents(bzz.buffer) <= max
          case _          => false
        })
    }

  property ("buffer evicts the correct elements") =
    forAll(bZipGen[Int](bufferGenAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        val (lrs, realPath) = resultsAndPathTaken[Id, Int, (Vector[Int], Vector[Int])](bz, path, bz2 => (bz2.buffer.lefts, bz.buffer.rights))
        lrs.zip(lrs.drop(1))
          .zip(realPath)
          .map { case (((l0, r0), (l1, r1)), np) => np match {
            case N => l1.size >= l0.size && r1.size <= r0.size
            case P => r1.size >= r0.size && l1.size <= l0.size
          } }
          .forall(_ == true)
    }

  property("effect only takes place once with a stream of one element regardless of buffer size") =
    forAll(WithEffect[Counter].bZipGen[Int](bufferSizeGen, bumpCounter)) {
      (cbz: Counter[BufferedZipper[Counter, Int]]) => cbz.exec(0) == 1
    }

  property("with unlimited buffer, effect happens at most once per element") =
    forAll(WithEffect[Counter].bZipGen[Int](bufferGenAtLeast(16), bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, Int]], path: Path) =>
        cbz.flatMap(move(path, _)).exec(0) <= cbz.flatMap(_.toStream).eval(0).size
    }
}