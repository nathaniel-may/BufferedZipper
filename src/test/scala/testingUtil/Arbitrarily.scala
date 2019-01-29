package testingUtil

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbBool, arbInt}
import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.effect.IO
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import scala.Stream.Empty

object Arbitrarily {

  trait PrevNext
  trait Stationary extends PrevNext
  trait N extends PrevNext
  trait P extends PrevNext
  object Next extends N { override def toString: String = "Next" }
  object Prev extends P { override def toString: String = "Prev" }
  object StationaryNext extends N with Stationary { override def toString: String = "StationaryNext" }
  object StationaryPrev extends P with Stationary { override def toString: String = "StationaryPrev" }

  case class BufferSize(max: Option[Long], limits: Option[MinMax])
  case class StreamAndPath[M[_]: Monad, A](stream: Stream[M[A]], path: Path, limits: Option[MinMax])
  case class MinMax(min: Long, max: Long) {
    def contains(i: Long): Boolean = i >= min && i <= max
  }
  case class Loop(steps: Stream[Stationary])
  case object Loop { val empty: Loop = new Loop(Stream()) }
  case class Path private (endAndBack: Stream[PrevNext], ls: Vector[Loop]) {
    val steps: Stream[PrevNext] = {
      def go(eab: Stream[PrevNext], loopStream: Stream[Loop], acc: Stream[PrevNext]): Stream[PrevNext] = (eab, loopStream) match {
        case (Empty, _)                   => acc
        case (pn #:: pns, Empty)          => go(pns, Empty, pn #:: acc)
        case (pn #:: pns, loop #:: loops) => go(pns, loops, pn #:: loop.steps #::: acc)
      }

      go(endAndBack.reverse, ls.toStream.reverse, Stream())
    }

    def removeLoop(index: Int):  Path = Path(endAndBack, ls.updated(index, Loop.empty))
    def keepOneLoop(index: Int): Path = Path(endAndBack, ls.map(_ => Loop.empty).updated(index, ls.lift(index).getOrElse(Loop.empty)))
    def removeAllLoops: Path = Path(endAndBack, ls.map(_ => Loop.empty))
  }
  object Path {
    def apply(loops: Vector[Loop]): Path = {
      val evenLoops = if (loops.size % 2 == 0) loops else loops :+ Loop(Stream())
      new Path(
        Stream.fill(evenLoops.size/2)(Next) #::: Stream.fill(evenLoops.size/2)(Prev),
        loops)
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

  def streamAndPathsGen[M[_]: Monad]: Gen[StreamAndPath[M, Int]] =
    boundedStreamAndPathsGen(None, Some(10)) // TODO hard limit on large testing

  // TODO can I abstract over more than Int
  // minSize and maxSize must be positive and minSize must be <= maxSize
  def boundedStreamAndPathsGen[M[_]: Monad](minSize: Option[Int], maxSize: Option[Int]): Gen[StreamAndPath[M, Int]] =
    Gen.sized { size =>
      val adjustedSize = size min maxSize.getOrElse(Int.MaxValue) max minSize.getOrElse(0)
      pathGen(adjustedSize).flatMap { path =>
        for {
          s <- Gen.listOfN(adjustedSize, arbInt.arbitrary)
        } yield StreamAndPath[M, Int](
          s.map(implicitly[Monad[M]].point(_)).toStream,
          path,
          minMax(maxSize.map(_.toLong), minSize.map(_.toLong)))
      }
    }

  implicit val aBufferSize:           Arbitrary[BufferSize]              = Arbitrary(bufferSizeGen(None, None, true))
  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPath[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPath[IO, Int]] = Arbitrary(streamAndPathsGen)

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
  implicit val shrinkStreamAndPath: Shrink[StreamAndPath[Id, Int]] = Shrink { sp =>
    (sp.limits.fold(0)(_.min.toInt) to sp.limits.fold(sp.stream.size-1)(_.max.toInt))
      .map(sp.stream.take)
      .toStream
      .map(s => StreamAndPath(s, sp.path, sp.limits))
  }

  private def pathGen(streamLength: Int): Gen[Path] = {
    def go(genSize: Int, z: Zipper[Unit], dir: PrevNext, path: Gen[Path]): Gen[Path] = {
      val newPath = for {
        s <- loopGen(z)
        p <- path
      } yield p.appendLoop(s).getOrElse(
        throw new Exception(s"Too many loops or generated a bad stationary loop:\n leftSize : ${z.lefts.size}\n rightSize: ${z.rights.size}\n loopsSize: ${p.loops.size}\n zSize    : ${p.zipperSize}\n loop: ${s.toList}"))

      dir match {
        case _: N => z.next.fold(go(genSize, z.previous.get, Prev, newPath))(zNext => go(genSize, zNext, Next, newPath))
        case _: P => z.previous.fold(path)(zNext => go(genSize, zNext, Next, newPath))
      }
    }

      Gen.sized { size => go(size, Stream.fill(streamLength)(()).toZipper.get, Next, Gen.const(Path(streamLength))) }
  }

  // subset problem for generating a set of balanced parens
  private def loopGen(zip: Zipper[Unit]): Gen[Loop] = Gen.sized { size =>
    def go(homeDist: Int, remaining: Int, acc: Gen[Stream[Stationary]]): Gen[Stream[Stationary]] =
      if (remaining <= homeDist && homeDist >= 0) acc.map(Stream.fill(homeDist)(StationaryPrev) #::: _)
      else if(remaining <= homeDist && homeDist <= 0) acc.map(Stream.fill(homeDist)(StationaryNext) #::: _)
      else for {
        stream  <- acc
        snp     <- Gen.pick(1, List(StationaryPrev, StationaryNext))
        np      <- snp.head
        (validNp, dist) = np match {
                            case _: N => (zip.next.fold[Stationary](StationaryPrev)(_ => StationaryNext), 1)
                            case _: P => (zip.previous.fold[Stationary](StationaryNext)(_ => StationaryPrev), -1)
                          }
      } yield go(homeDist + dist, remaining-1, validNp #:: stream)

    go(0, size, Gen.const(Stream())).map(steps => Loop(steps.reverse))
  }

  private val prevNextGen: Gen[PrevNext] = arbBool.arbitrary.map(b => if(b) Next else Prev)

  private def minMax(min: Option[Long], max: Option[Long]) = (max, min) match {
    case (None, None)             => None
    case (None, Some(max_))       => Some(MinMax(0,    max_))
    case (Some(min_), None)       => Some(MinMax(min_, Int.MaxValue.toLong))
    case (Some(min_), Some(max_)) => Some(MinMax(min_, max_))
  }

}
