package testingUtil

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbBool, arbInt}
import scalaz.Monad
import scalaz.Scalaz.Id
import scalaz.effect.IO

object Arbitrarily {

  trait PrevNext
  trait N extends PrevNext
  trait P extends PrevNext
  object Next extends N { override def toString: String = "Next" }
  object Prev extends P { override def toString: String = "Prev" }
  object StationaryNext extends N { override def toString: String = "StationaryNext" }
  object StationaryPrev extends P { override def toString: String = "StationaryPrev" }

  case class BufferSize(max: Option[Long], limits: Option[MinMax])
  case class StreamAndPaths[M[_]: Monad, A](stream: Stream[M[A]], pathGen: Gen[Path], limits: Option[MinMax])
  case class Path(steps: Stream[PrevNext])
  case class MinMax(min: Long, max: Long) {
    def contains(i: Long): Boolean = i >= min && i <= max
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
    boundedStreamAndPathsGen(None, None)

  // TODO can I abstract over more than Int
  // minSize and maxSize must be positive and minSize must be <= maxSize
  def boundedStreamAndPathsGen[M[_]: Monad](minSize: Option[Int], maxSize: Option[Int]): Gen[StreamAndPaths[M, Int]] =
    Gen.sized { size =>
      val adjustedSize = (for {
        maxS <- maxSize
        minS <- minSize
      } yield size min maxS max minS).getOrElse(size)

      for {
        s <- Gen.listOfN(adjustedSize, arbInt.arbitrary)
      } yield StreamAndPaths[M, Int](
        s.map(implicitly[Monad[M]].point(_)).toStream,
        pathGen(adjustedSize),
        minMax(maxSize.map(_.toLong), minSize.map(_.toLong)))
    }

  implicit val aBufferSize:           Arbitrary[BufferSize]              = Arbitrary(bufferSizeGen(None, None, true))
//  implicit val aLimitedBufferSize:    Arbitrary[LimitedBufferSize]       = Arbitrary(limitedBufferSizeGen)
//  implicit val aNonZeroBufferSize:    Arbitrary[NonZeroBufferSize]       = Arbitrary(nonZeroBufferSizeGen)
  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPaths[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPaths[IO, Int]] = Arbitrary(streamAndPathsGen)

  implicit val shrinkBufferSize: Shrink[BufferSize] = Shrink { buffSize => buffSize.max match {
    case None       => Stream()
    case Some(size) => Stream(0, 16, size/2, size-16)
      .filter(buffSize.limits.contains)
      .map(long => BufferSize(Some(long), buffSize.limits))
  }}

  private def pathGen(streamLength: Int): Gen[Path] = {
    def go(genSize: Int, len: Int, there: Gen[Stream[PrevNext]], back: Gen[Stream[PrevNext]]): Gen[Stream[PrevNext]] =
      if (len <= 0) for {pathThere <- there; pathBack <- back} yield pathThere.reverse #::: pathBack.reverse //TODO check this reversing
      else go(
        genSize,
        len - 1,
        there.flatMap { s => stationaryPath(genSize, streamLength - len, len - 1).flatMap(_.steps #::: Next #:: s) },
        back.flatMap  { s => stationaryPath(genSize, len - 1, streamLength - len).flatMap(_.steps #::: Prev #:: s) })

      Gen.sized { size => go(size, streamLength - 1, Stream(), Stream()).map(s => Path(s)) }
  }

  // subset problem for generating a set of balanced parens
  //TODO is this tail recursive with the lazy vals?
  //TODO should I choose a size somewhere between 0 and genSize instead of them always being huge?
  private def stationaryPath(genSize: Int, lBound: Int, rBound: Int): Gen[Path] = {
    def go(layer: Int, prev: Int, next: Int, lb: Int, rb: Int, path: Gen[Stream[PrevNext]]): Gen[Stream[PrevNext]] = {
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
      go(pathSize*2, 0, 0, lBound, rBound, Stream()).map(Path) }
  }

  private val prevNextGen: Gen[PrevNext] = arbBool.arbitrary.map(b => if(b) Next else Prev)

  private def minMax(min: Option[Long], max: Option[Long]) = (max, min) match {
    case (None, None)             => None
    case (None, Some(max_))       => Some(MinMax(0,    max_))
    case (Some(min_), None)       => Some(MinMax(min_, Int.MaxValue.toLong))
    case (Some(min_), Some(max_)) => Some(MinMax(min_, max_))
  }

}
