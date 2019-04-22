package zipper

// Scalacheck
import org.scalacheck.Prop.{forAll, forAllNoShrink}
import org.scalacheck.{Arbitrary, Properties}

// Project
import util.PropertyFunctions._
import util.Generators._


//TODO add properties for inheritance
object BufferedZipperProperties extends Properties("BufferedZipper") {
  val noEffect = WithEffect[Id]()
  import noEffect.{bZipGen, bZipGenMax, bZipGenMin, uniqueBZipGen} // allows for these functions to be called without explicit Id effect

  implicit val aPath: Arbitrary[Path] = Arbitrary(pathGen)
  implicit val aBufferSize: Arbitrary[Limit] = Arbitrary(limitGen)
  implicit val anInheritance: Arbitrary[Inheritance] = Arbitrary(inheritanceGen)

  property("toStream is the same as the streamInput regardless of starting point and buffer size") = forAll {
    (inStream: Stream[String], limits: Limit, path: Path) => BufferedZipper[Id, String](inStream, limits)
        .fold[Stream[String]](Stream()) { move[Id, String](path, _).toStream } == inStream
  }

  property("toStream is the same as the streamInput regardless of starting point and buffer size with monad transformers") = forAll {
    (inStream: Stream[Inheritance], limits: Limit, path: Path) => BufferedZipper[Id, Inheritance](inStream, limits)
      .fold[Stream[Inheritance]](Stream()) { moveT[Id, Inheritance](path, _).value.get.toStream } == inStream
  }

  property("toStream is the same as the streamInput with subtypes regardless of starting point and buffer size") = forAll {
    (inStream: Stream[String], limits: Limit, path: Path) => BufferedZipper[Id, String](inStream, limits)
        .fold[Stream[String]](Stream()) { move[Id, String](path, _).toStream } == inStream
  }

  property("toStream uses buffer to minimize effectful calls") =
    forAll(WithEffect[Counter].bZipGen[String](limitGen, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, String]], path: Path) =>
        val start = cbz.flatMap { bz => move(path, bz).flatMap(zeroCounter) }
        val effects = start.flatMap(b => b.toStream).runS(0).value
        val shouldBe = start.map { bz =>
          bz.toStream.runA(0).value.size - bz.buffer.size - 1 }.runA(0).value
        effects == shouldBe
    }

  // TODO handle get better
  property("toStream uses buffer to minimize effectful calls with monad transformers") =
    forAll(WithEffect[Counter].bZipGen[String](limitGen, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, String]], path: Path) =>
        val start = cbz.flatMap { bz => moveT(path, bz).value.flatMap(zeroCounter) }
        val effects = start.flatMap(_.get.toStream).runS(0).value
        val shouldBe = start.map { obz =>
          obz.get.toStream.runA(0).value.size - obz.get.buffer.size - 1 }.runA(0).value
        effects == shouldBe
    }

  property("toStream doesn't minimize effectful calls with no buffer") =
    forAll(WithEffect[Counter].bZipGen[String](noBuffer, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, String]], path: Path) =>
        val start = cbz.flatMap { bz => move(path, bz).flatMap(zeroCounter) }
        val effects = start.flatMap(_.toStream).runS(0).value
        val shouldBe = start.map { bz =>
          bz.toStream.runA(0).value.size - 1 }.runA(0).value
        effects == shouldBe
    }

  // TODO handle get better
  property("toStream doesn't minimize effectful calls with no buffer and monad transformers") =
    forAll(WithEffect[Counter].bZipGen[String](noBuffer, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, String]], path: Path) =>
        val start = cbz.flatMap { bz => moveT(path, bz).value.flatMap(zeroCounter) }
        val effects = start.flatMap(_.get.toStream).runS(0).value
        val shouldBe = start.map { obz =>
          obz.get.toStream.runA(0).value.size - 1 }.runA(0).value
        effects == shouldBe
    }

  property("map uses buffer to minimize effectful calls") =
    forAll(WithEffect[Counter].bZipGen[String](limitGen, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, String]], path: Path) =>
        val start = cbz.flatMap { bz => move(path, bz).flatMap(zeroCounter) }
        val effects = start.flatMap(_.map(_ + 1).toStream).runS(0).value
        val shouldBe = start.map { bz =>
          bz.toStream.runA(0).value.size - bz.buffer.size - 1 }.runA(0).value
        effects == shouldBe
    }

  property("next then prev should result in the first element regardless of buffer limit") =
    forAll(bZipGenMin[String](2, limitGen)) {
      (b: BufferedZipper[Id, String]) => (for {
        next   <- b.next
        prev   <- next.prev
      } yield prev.focus) == b.toStream.headOption
    }

  property("nextT then prevT should result in the first element regardless of buffer limit") =
    forAll(bZipGenMin[String](2, limitGen)) {
      (b: BufferedZipper[Id, String]) => (for {
        next   <- b.nextT
        prev   <- next.prevT
      } yield prev.focus).value == b.toStream.headOption
    }

  property("buffer is being used when there are at least two elements and space for at least one element") =
    forAllNoShrink(bZipGenMin[Int](2, byteLimitAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
          resultsOnPath[Id, Int, Long](bz, path, bzz => measureBufferContents(bzz.buffer))
            .drop(1)
            .forall(_ > 0)
    }

  property("buffer is not being used for streams of one or less elements") =
    forAll(bZipGenMax[Int](1, byteLimitAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](bz, path, bzz => measureBufferContents(bzz.buffer) == 0)
    }

  property("buffer never has duplicate items") =
    forAll(uniqueBZipGen[String](byteLimitAtLeast(16)), pathGen) {
      (in: BufferedZipper[Id, String], path: Path) =>
        assertOnPath[Id, String](in, path, bz =>
          bz.buffer.toVector.toList.groupBy(identity).valuesIterator.forall(_.size == 1))
    }

  property("buffer is always a segment of the input") =
      forAll(uniqueBZipGen[String](byteLimitAtLeast(16)), pathGen) {
        (in: BufferedZipper[Id, String], path: Path) =>
          assertOnPath[Id, String](in, path, bz =>
            in.toStream.containsSlice(bz.buffer.toVector.toList))
      }

  property("buffer never contains the focus") =
    forAll(uniqueBZipGen[String](byteLimitAtLeast(16)), pathGen) {
      (in: BufferedZipper[Id, String], path: Path) =>
        assertOnPath[Id, String](in, path, bz => !bz.buffer.contains(bz.focus))
    }

  property("buffer byte limit is never exceeded") =
    forAll(bZipGen[String](byteLimitAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, String], path: Path) =>
        assertOnPath[Id, String](bz, path, bzz => bzz.buffer.limit match {
          case ByteLimit(max, _) => measureBufferContents(bzz.buffer) <= max
          case _                 => false
        })
    }

  property("buffer size limit is never exceeded") =
    forAll(bZipGen[String](sizeLimitAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, String], path: Path) =>
        assertOnPath[Id, String](bz, path, bzz => bzz.buffer.limit match {
          case SizeLimit(max) => bzz.buffer.size <= max
          case _              => false
        })
    }

  property("effect only takes place once with a stream of one element regardless of buffer size") =
    forAll(WithEffect[Counter].bZipGen[String](limitGen, bumpCounter)) {
      (cbz: Counter[BufferedZipper[Counter, String]]) => cbz.runS(0).value == 1
    }

  property("with unlimited buffer, effect happens at most once per element") =
    forAll(WithEffect[Counter].bZipGen[String](noLimitGen, bumpCounter), pathGen) {
      (cbz: Counter[BufferedZipper[Counter, String]], path: Path) =>
        cbz.flatMap(move(path, _)).runS(0).value <= cbz.flatMap(_.toStream).runA(0).value.size
    }
}