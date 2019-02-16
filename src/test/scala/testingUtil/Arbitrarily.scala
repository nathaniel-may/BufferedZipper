package testingUtil

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbBool, arbInt}
import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.effect.IO
import scalaz.syntax.std.stream.ToStreamOpsFromStream

import Directions._
import util.BufferTypes._

object Arbitrarily {

  case class StreamAndPath[M[_]: Monad, A](stream: Stream[M[A]], path: Path, limits: Option[MinMax])
  case class MinMax(min: Long, max: Long) {
    def contains(i: Long): Boolean = i >= min && i <= max
  }

  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPath[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPath[IO, Int]] = Arbitrary(streamAndPathsGen)
  implicit val aFlexibleBufferSize:   Arbitrary[FlexibleBuffer]         = Arbitrary(flexibleBufferSizeGen)

  def shrinkLoopToDepth(loop: Loop, n: Int): Loop = {
    def go(l: Loop): Loop = {
      if (l.nReach <= n) l
      else go(l.shrinkNReach)
    }

    if (n < 0) loop else go(loop)
  }

  private def zipRight[A](zip: Zipper[A], i: Int): Zipper[A] = {
    if (i == 0) zip
    else zipRight(zip.next.get, i-1) // TODO this is kind of messy
  }

  private def minMax(min: Option[Long], max: Option[Long]): Option[MinMax] = (max, min) match {
    case (None, None)             => None
    case (None, Some(max_))       => Some(MinMax(0,    max_))
    case (Some(min_), None)       => Some(MinMax(min_, Int.MaxValue.toLong))
    case (Some(min_), Some(max_)) => Some(MinMax(min_, max_))
  }

}
