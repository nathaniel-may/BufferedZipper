package testingUtil

import org.scalacheck.{Arbitrary, Gen}, Arbitrary.{arbInt, arbBool}
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

  case class BufferSize(max: Option[Long])
  case class LimitedBufferSize(max: Long)
  case class NonZeroBufferSize(max: Option[Long])
  case class StreamAndPaths[M[_]: Monad, A](stream: Stream[M[A]], pathGen: Gen[Path])
  case class Path(steps: Stream[PrevNext])

  val bufferSizeGen: Gen[BufferSize] = Gen.sized { size =>
    BufferSize(if (size <= 0) None else if(size == 1) Some(Long.MaxValue) else Some(16 * (size - 2))) }

  val limitedBufferSizeGen: Gen[LimitedBufferSize] = Gen.sized { size =>
    LimitedBufferSize(if (size <= 0) Long.MaxValue else 16 * (size - 1)) }

  val nonZeroBufferSizeGen: Gen[NonZeroBufferSize] = Gen.sized { size =>
    NonZeroBufferSize(if (size <= 0) None else if(size == 1) Some(Long.MaxValue) else Some(16 * (size - 1))) }

  def streamAndPathsGen[M[_]: Monad]: Gen[StreamAndPaths[M, Int]] =
    boundedStreamAndPathsGen(Int.MinValue, Int.MaxValue)

  // TODO can I abstract over more than Int
  // minSize and maxSize must be positive and minSize must be <= maxSize
  def boundedStreamAndPathsGen[M[_]: Monad](minSize: Int, maxSize: Int): Gen[StreamAndPaths[M, Int]] =
    Gen.sized { size =>
      val adjustedSize = size min maxSize max minSize
      for {
        s <- Gen.listOfN(adjustedSize, arbInt.arbitrary)
      } yield StreamAndPaths[M, Int](s.map(implicitly[Monad[M]].point(_)).toStream, pathGen(adjustedSize))
    }

  implicit val aBufferSize:           Arbitrary[BufferSize]              = Arbitrary(bufferSizeGen)
  implicit val aLimitedBufferSize:    Arbitrary[LimitedBufferSize]       = Arbitrary(limitedBufferSizeGen)
  implicit val aNonZeroBufferSize:    Arbitrary[NonZeroBufferSize]       = Arbitrary(nonZeroBufferSizeGen)
  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPaths[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPaths[IO, Int]] = Arbitrary(streamAndPathsGen)

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

}
