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

  trait BufferSize { val cap: Long }

  case class FlexibleBuffer private(cap: Long) extends BufferSize
  object FlexibleBuffer {
    def apply(cap: Long): Option[FlexibleBuffer] =
      if (cap >= 0) Some(new FlexibleBuffer(cap)) else None
  }

  case class CappedBuffer private (cap: Long, max: Long) extends BufferSize
  object CappedBuffer {
    def apply(cap: Long, max: Long): Option[CappedBuffer] =
      if (cap <= max && cap >= 0 && max >= 0) Some(new CappedBuffer(cap, max)) else None
  }

  case class LargerBuffer private (cap: Long, min: Long) extends BufferSize
  object LargerBuffer {
    def apply(cap: Long, min: Long): Option[LargerBuffer] =
      if (cap >= min && cap >= 0 && min >= 0) Some(new LargerBuffer(cap, min)) else None
  }

  case class RangeBuffer private (cap: Long, min: Long, max: Long) extends BufferSize
  object RangeBuffer {
    def apply(cap: Long, min: Long, max: Long): Option[RangeBuffer] =
      if (min <= max && cap <= max && cap >= min && cap >= 0 && max >= 0 && min >= 0)
        Some(new RangeBuffer(cap, min, max))
      else
        None
  }

  case class StreamAndPath[M[_]: Monad, A](stream: Stream[M[A]], path: Path, limits: Option[MinMax])
  case class MinMax(min: Long, max: Long) {
    def contains(i: Long): Boolean = i >= min && i <= max
  }
  case class Loop(steps: Stream[Stationary])
  case object Loop { val empty: Loop = Loop(Stream()) }
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
      val evenLoops = if (loops.size % 2 == 0) loops else loops :+ Loop.empty
      new Path(
        Stream.fill(evenLoops.size/2)(Next) #::: Stream.fill(evenLoops.size/2)(Prev),
        loops)
    }

    val empty = Path(Vector(Loop.empty))
  }

  val finiteIntStreamGen: Gen[Stream[Int]] = Gen.sized { size =>
    Gen.containerOfN[Stream, Int](size, Arbitrary.arbInt.arbitrary)
  }

  def nonZeroBufferSizeGen(min: Long): Gen[LargerBuffer] = Gen.sized { size =>
    Gen.const(LargerBuffer(16L * size + min, min).get)
  }

  def cappedBufferSizeGen(max: Long): Gen[CappedBuffer] = Gen.sized { size =>
    Gen.const(CappedBuffer(16L * size, max)
        .fold(CappedBuffer(max, max).get)(identity))
  }

  val flexibleBufferSizeGen: Gen[FlexibleBuffer] = Gen.sized { size =>
    Gen.const(FlexibleBuffer(16L * size).get)
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

  implicit val anIdIntStreamAndPaths: Arbitrary[StreamAndPath[Id, Int]] = Arbitrary(streamAndPathsGen)
  implicit val anIOIntStreamAndPaths: Arbitrary[StreamAndPath[IO, Int]] = Arbitrary(streamAndPathsGen)
  implicit val aFlexibleBufferSize:   Arbitrary[FlexibleBuffer]         = Arbitrary(flexibleBufferSizeGen)

  implicit val shrinkBufferSize: Shrink[BufferSize] = Shrink[BufferSize] {
    buffSize => Stream.tabulate((buffSize.cap / 16L).toInt)(i => buffSize.cap - i*16L)
      .flatMap(shrunkCap => FlexibleBuffer(shrunkCap).fold[Stream[FlexibleBuffer]](Stream())(Stream(_)))
  }

  implicit val shrinkPath: Shrink[Path] = Shrink { path =>
    path.removeAllLoops #:: path.ls.indices.toStream.map(path.keepOneLoop)
  }

  // TODO this is kind of a dumb shrinker
  implicit val shrinkStreamAndPath: Shrink[StreamAndPath[Id, Int]] = Shrink { sp =>
    println(s"shrinking stream and path: $sp")
    val x = (sp.limits.fold(0)(_.min.toInt) to sp.limits.fold(sp.stream.size-1)(_.max.toInt))
      .map(sp.stream.take)
      .toStream
      .map(s => StreamAndPath(s, sp.path, sp.limits))
      .filter(spShrunk => spShrunk.stream != sp.stream || spShrunk.path != spShrunk.path)
    println(s"shrunk options: ${x.toList}")
    x
  }

  private def pathGen(streamLength: Int): Gen[Path] = Gen.sized { size => // TODO size never forwarded to loop
    def go(zip: Zipper[Unit], progress: Zipper[Unit] => Option[Zipper[Unit]], acc: Gen[Vector[Loop]]): Gen[Vector[Loop]] = progress(zip) match {
      case None       => acc
      case Some(pZip) => go(pZip, progress, acc.flatMap(v => loopGen(zip).map(v :+ _)))
    }

    if (streamLength == 0) Path.empty
    else for {
      there <- go(Stream.fill(streamLength)(()).toZipper.get, _.next,     Gen.const(Vector()))
      back  <- go(Stream.fill(streamLength)(()).toZipper.get, _.previous, Gen.const(Vector()))
    } yield Path(there.reverse ++ back.reverse)
  }

  // subset problem for generating a set of balanced parens
  private def loopGen(zip: Zipper[Unit]): Gen[Loop] = Gen.sized { size =>
    def go(homeDist: Int, remaining: Int, acc: Gen[Stream[Stationary]]): Gen[Stream[Stationary]] =
      if (remaining <= homeDist && homeDist >= 0) acc.map(Stream.fill(homeDist)(StationaryPrev) #::: _)
      else if(remaining <= homeDist && homeDist <= 0) acc.map(Stream.fill(homeDist)(StationaryNext) #::: _)
      else for {
        stream  <- acc
        np      <- stationaryGen
        validNp =  np match {
                     case _: N => zip.next    .fold[Stationary](StationaryPrev)(_ => StationaryNext)
                     case _: P => zip.previous.fold[Stationary](StationaryNext)(_ => StationaryPrev)
                   }
        dist    =  validNp match { case _: N => 1; case _: P => -1 }
        recurse <- go(homeDist + dist, remaining-1, Gen.const(validNp #:: stream))
      } yield recurse

    go(0, size, Gen.const(Stream())).map(steps => Loop(steps.reverse))
  }

  private val stationaryGen: Gen[Stationary] = arbBool.arbitrary.map(b => if(b) StationaryNext else StationaryPrev)

  private def minMax(min: Option[Long], max: Option[Long]): Option[MinMax] = (max, min) match {
    case (None, None)             => None
    case (None, Some(max_))       => Some(MinMax(0,    max_))
    case (Some(min_), None)       => Some(MinMax(min_, Int.MaxValue.toLong))
    case (Some(min_), Some(max_)) => Some(MinMax(min_, max_))
  }

  //TODO remove explainer
  def explain[T: Shrink] = Shrink[T] { input =>
    println(s"input to shrink: $input")
    val wrappedShrinker = implicitly[Shrink[T]]
    val shrunkValues = wrappedShrinker.shrink(input)
    //this eagerly evaluates the Stream of values. It could blow up on very large Streams or expensive computations.
    println(s"shrunk values: ${shrunkValues.mkString(",")}")
    shrunkValues
  }

}
