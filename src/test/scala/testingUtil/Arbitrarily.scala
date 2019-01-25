package testingUtil

import org.scalacheck.{Arbitrary, Gen}, Arbitrary.{arbInt, arbBool}
import scalaz.Monad
import scalaz.Scalaz.Id
import scalaz.effect.IO

object Arbitrarily {

  trait PrevNext
  object Prev extends PrevNext
  object Next extends PrevNext

  case class BufferSize(max: Option[Long])
  case class LimitedBufferSize(max: Long)
  case class NonZeroBufferSize(max: Option[Long])
  case class StreamAndPaths[M[_]: Monad, A](buffZip: Stream[M[A]], pathGen: Gen[Path])
  case class Path(steps: Stream[PrevNext])

  //TODO Fix these
  case class StreamAtLeast2[A](wrapped: Stream[A])
  case class UniqueStreamAtLeast1[A](wrapped: Stream[A])

  val bufferSizeGen: Gen[BufferSize] = Gen.sized { size =>
    BufferSize(if (size <= 0) None else if(size == 1) Some(Long.MaxValue) else Some(16 * (size - 2))) }

  val limitedBufferSizeGen: Gen[LimitedBufferSize] = Gen.sized { size =>
    LimitedBufferSize(if (size <= 0) Long.MaxValue else 16 * (size - 1)) }

  val nonZeroBufferSizeGen: Gen[NonZeroBufferSize] = Gen.sized { size =>
    NonZeroBufferSize(if (size <= 0) None else if(size == 1) Some(Long.MaxValue) else Some(16 * (size - 1))) }

  // TODO can I abstract over more than Int
  def streamAndPathsGen[M[_]: Monad]: Gen[StreamAndPaths[M, Int]] = Gen.sized { size => for {
    s <- Gen.listOfN(size, arbInt.arbitrary)
  } yield StreamAndPaths[M, Int](s.map(implicitly[Monad[M]].point(_)).toStream, pathGen(size)) }

  implicit val aBufferSize:           Arbitrary[BufferSize]              = Arbitrary(bufferSizeGen)
  implicit val aLimitedBufferSize:    Arbitrary[LimitedBufferSize]       = Arbitrary(limitedBufferSizeGen)
  implicit val aNonZeroBufferSize:    Arbitrary[NonZeroBufferSize]       = Arbitrary(nonZeroBufferSizeGen)
  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPaths[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPaths[IO, Int]] = Arbitrary(streamAndPathsGen)

  implicit val aUniqueStreamAtLeast1: Arbitrary[UniqueStreamAtLeast1[Int]] =
    Arbitrary(Gen.atLeastOne(-20 to 20).map(seq => UniqueStreamAtLeast1(seq.toStream)))

  implicit val aStreamAtLeast2: Arbitrary[StreamAtLeast2[Int]] = Arbitrary((for {
    l1 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
    l2 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
  } yield l1 ::: l2).map(l => StreamAtLeast2(l.toStream)))

  private def pathGen(streamLength: Int): Gen[Path] = {
    def go(genSize: Int, len: Int, path: Gen[Stream[PrevNext]]): Gen[Stream[PrevNext]] =
      if (len <= streamLength * -1) path
      else if (len > 0) go(genSize, len-1, path.flatMap(s => stationaryPath(genSize, len,                streamLength - len).flatMap(Next #:: _.steps #::: s)))
      else              go(genSize, len-1, path.flatMap(s => stationaryPath(genSize, streamLength + len, len * -1).flatMap(Prev #:: _.steps #::: s)))

    Gen.sized { size => go(size, streamLength, Stream()).map(Path) }
  }

  // subset problem for generating a set of balanced parens
  //TODO is this tail recursive with the lazy vals?
  private def stationaryPath(genSize: Int, lBound: Int, rBound: Int): Gen[Path] = {
    def go(layer: Int, prev: Int, next: Int, lb: Int, rb: Int, path: Gen[Stream[PrevNext]]): Gen[Stream[PrevNext]] = {
      lazy val addNext = go(layer - 1, prev,     next + 1, lb + 1, rb - 1, path.map(Next #:: _))
      lazy val addPrev = go(layer - 1, prev + 1, next,     lb - 1, rb + 1, path.map(Prev #:: _))

      if (layer <= 0 || (lb <= 0 && rb <= 0)) path
      else if (lb <= 0) addNext
      else if (lb <= 0) addPrev
      else prevNextGen.flatMap {
        case Next => addNext
        case Prev => addPrev
      }
    }

    go(genSize*2, 0, 0, lBound, rBound, Stream()).map(Path)
  }

  private val prevNextGen: Gen[PrevNext] = arbBool.arbitrary.map(b => if(b) Next else Prev)

}
