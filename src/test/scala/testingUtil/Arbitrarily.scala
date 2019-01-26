package testingUtil

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbBool, arbInt}
import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.effect.IO
import scalaz.Scalaz.unfold
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import scala.Stream.Empty

object Arbitrarily {

  trait PrevNext
  trait Stationary
  trait N extends PrevNext
  trait P extends PrevNext
  object Next extends N { override def toString: String = "Next" }
  object Prev extends P { override def toString: String = "Prev" }
  object StationaryNext extends N with Stationary { override def toString: String = "StationaryNext" }
  object StationaryPrev extends P with Stationary { override def toString: String = "StationaryPrev" }

  case class BufferSize(max: Option[Long], limits: Option[MinMax])
  case class StreamAndPaths[M[_]: Monad, A](stream: Stream[M[A]], pathGen: Gen[Path], limits: Option[MinMax])
  case class MinMax(min: Long, max: Long) {
    def contains(i: Long): Boolean = i >= min && i <= max
  }
  case class Path (zipperSize: Int, loops: Vector[Stream[Stationary]]) {
    val steps: Stream[PrevNext] =
      unfold[(Int, Stream[PrevNext]), PrevNext]((0, Next #:: loops.lift(0).fold[Stream[PrevNext]](Stream())(_.map(Path.toPrevNext)))){
        case (moved, Empty)   if moved < zipperSize - 1      => Some((Next, (moved + 1, loops.lift(moved + 1).fold[Stream[PrevNext]](Stream())(_.map(Path.toPrevNext)))))
        case (moved, Empty)                                  => Some((Prev, (moved + 1, loops.lift(moved + 1).fold[Stream[PrevNext]](Stream())(_.map(Path.toPrevNext)))))
        case (moved, h #:: t)                                => Some((h, (moved, t) ))
        case (moved, _)       if moved >= zipperSize * 2 - 1 => None
      }

    def appendLoop(loop: Stream[Stationary]): Option[Path] =
      if (loops.size == zipperSize) None
      else if (Path.isValidLoop(loops.size + 1, zipperSize, loop))
        Some(Path(zipperSize, loops :+ loop))
      else None

    def removeLoop(index: Int): Path = Path(zipperSize, loops.updated(index, Stream()))
    def keepOneLoop(index: Int): Path = Path(zipperSize, loops.map(_ => Stream()).updated(index, loops.lift(index).getOrElse(Stream())))
    def removeAllLoops: Path = Path(zipperSize, Vector())
  }
  object Path {
    def apply(zipperSize: Int): Path = Path(zipperSize, Vector())
    def isValidLoop(index: Int, size: Int, loop: Stream[Stationary]): Boolean = ???
    private def toPrevNext(np: Stationary): PrevNext = np match {
      case _: N => StationaryNext
      case _: P => StationaryPrev
    }
  }

  def nonZeroBufferSizeGen(min: Long): Gen[BufferSize] = bufferSizeGen(Some(min), None, noneOk = false)

  def bufferSizeGen(min: Option[Long], max: Option[Long], noneOk: Boolean): Gen[BufferSize] = Gen.sized { size =>
    val minn: Long = min.getOrElse(0L)
    val maxx: Long = max.getOrElse(Int.MaxValue.toLong)
    BufferSize(
      if      (size <= 0 && noneOk)  None
      else if (size <= 0 && !noneOk) Some(maxx)
      else if (size == 1)            Some(maxx)
      else                           Some(minn + 16L * (size.toLong - 2L)),
      minMax(min, max))
  }

  def streamAndPathsGen[M[_]: Monad]: Gen[StreamAndPaths[M, Int]] =
    boundedStreamAndPathsGen(None, Some(10)) // TODO hard limit on large testing

  // TODO can I abstract over more than Int
  // minSize and maxSize must be positive and minSize must be <= maxSize
  def boundedStreamAndPathsGen[M[_]: Monad](minSize: Option[Int], maxSize: Option[Int]): Gen[StreamAndPaths[M, Int]] =
    Gen.sized { size =>
      val adjustedSize = size min maxSize.getOrElse(Int.MaxValue) max minSize.getOrElse(0)

      for {
        s <- Gen.listOfN(adjustedSize, arbInt.arbitrary)
      } yield StreamAndPaths[M, Int](
        s.map(implicitly[Monad[M]].point(_)).toStream,
        pathGen(adjustedSize),
        minMax(maxSize.map(_.toLong), minSize.map(_.toLong)))
    }

  implicit val aBufferSize:           Arbitrary[BufferSize]              = Arbitrary(bufferSizeGen(None, None, true))
  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPaths[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPaths[IO, Int]] = Arbitrary(streamAndPathsGen)

  implicit val shrinkBufferSize: Shrink[BufferSize] = Shrink { buffSize => buffSize.max match {
    case None       => Stream()
    case Some(size) => Stream(0, 16, size/2, size-16).filter(_ != size)
      .filter(buffSize.limits.contains)
      .map(long => BufferSize(Some(long), buffSize.limits))
  }}

  implicit val shrinkPath: Shrink[Path] = Shrink { path =>
    path.removeAllLoops #:: path.loops.indices.toStream.map(path.keepOneLoop)
  }

  // TODO this is kind of a dumb shrinker
  implicit val shrinkStreamAndPath: Shrink[StreamAndPaths[Id, Int]] = Shrink { sp =>
    (sp.limits.fold(0)(_.min.toInt) to sp.limits.fold(sp.stream.size-1)(_.max.toInt))
      .map(sp.stream.take)
      .toStream
      .map(s => StreamAndPaths(s, sp.pathGen, sp.limits))
  }

  private def goHome(steps: Stream[PrevNext]): Stream[PrevNext] =
    steps #::: Stream.fill(
      steps.map { case Next => 1; case Prev => -1 }
        .reduce[Int] { case (l, r) => l + r } )(Prev)

  private def pathGen(streamLength: Int): Gen[Path] = {
    def go(genSize: Int, z: Zipper[Unit], dir: PrevNext, path: Gen[Path]): Gen[Path] = {
      val newPath = for {
        p <- path
        s <- stationaryPath(genSize, z.lefts.size, z.rights.size)
      } yield p.appendLoop(s).getOrElse(throw new Exception("Generated a bad stationary loop"))

      dir match {
        case _: N => z.next.fold(go(genSize, z.previous.get, Prev, newPath))(zNext => go(genSize, zNext, Next, newPath))
        case _: P => z.next.fold(path)(zNext => go(genSize, zNext, Next, newPath))
      }
    }

      Gen.sized { size => go(size, Stream.fill(streamLength)(()).toZipper.get, Next, Gen.const(Path(streamLength))) }
  }

  // subset problem for generating a set of balanced parens
  //TODO is this tail recursive with the lazy vals?
  //TODO should I choose a size somewhere between 0 and genSize instead of them always being huge?
  private def stationaryPath(genSize: Int, lBound: Int, rBound: Int): Gen[Stream[Stationary]] = {
    def go(layer: Int, prev: Int, next: Int, lb: Int, rb: Int, path: Gen[Stream[Stationary]]): Gen[Stream[Stationary]] = {
      lazy val addNext = go(layer - 1, prev,     next + 1, lb + 1, rb - 1, path.map(StationaryNext #:: _))
      lazy val addPrev = go(layer - 1, prev + 1, next,     lb - 1, rb + 1, path.map(StationaryPrev #:: _))

      if (layer <= 0 || (lb <= 0 && rb <= 0)) path
      else if (lb <= 0) addNext
      else if (rb <= 0) addPrev
      else if (prev - next >= layer) addNext
      else if (next - prev >= layer) addPrev
      else prevNextGen.flatMap {
        case Next => addNext
        case Prev => addPrev
      }
    }

    // chooses a stationary path somewhere below the max so that these paths aren't strictly exponential in size
    Gen.choose(0, genSize).flatMap { pathSize =>
      go(pathSize*2, 0, 0, lBound, rBound, Stream()) }
  }

  private val prevNextGen: Gen[PrevNext] = arbBool.arbitrary.map(b => if(b) Next else Prev)

  private def minMax(min: Option[Long], max: Option[Long]) = (max, min) match {
    case (None, None)             => None
    case (None, Some(max_))       => Some(MinMax(0,    max_))
    case (Some(min_), None)       => Some(MinMax(min_, Int.MaxValue.toLong))
    case (Some(min_), Some(max_)) => Some(MinMax(min_, max_))
  }

}
