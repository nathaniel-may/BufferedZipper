package testingUtil

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbBool, arbInt}
import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.effect.IO
import scalaz.syntax.std.stream.ToStreamOpsFromStream

import Directions._

object Arbitrarily {

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
    path.noLoops #:: path.ls.toStream.indices.toStream.map(path.keepOneLoop)
  }

  //shrinks the path, then the stream
  implicit val shrinkStreamAndPath: Shrink[StreamAndPath[Id, Int]] = Shrink[StreamAndPath[Id, Int]] { sp =>
    println(s"shrinking: $sp")
    val loopStream = sp.path.ls.toStream

    val shrunkPaths: Stream[StreamAndPath[Id, Int]] = loopStream.indices.map(sp.path.keepOneLoop).toStream.append(Stream(sp.path.noLoops))
      .map(p => StreamAndPath[Id, Int](sp.stream, p, sp.limits))

    val shrunkStreams: Stream[StreamAndPath[Id, Int]] =
      sp.stream.indices.reverse.toStream.map {
        i => (sp.stream.take(i), sp.path.ls.take(i).toStream.zipWithIndex.map {
          case (loop, index) => loop.fold(identity, identity).shrinkNReachTo(i-index-1) }) } //TODO check this -1
        .map { case (s, loops) => StreamAndPath[Id, Int](s, Path(loops), sp.limits) }

    val shrunkStreamsLessLoops: Stream[StreamAndPath[Id, Int]] =
      shrunkStreams.flatMap { shrunkSp =>
        shrunkSp.path.ls.toStream.indices.toStream.map(shrunkSp.path.keepOneLoop)
          .append(Stream(shrunkSp.path.noLoops))
          .map(p => StreamAndPath[Id, Int](sp.stream, p, sp.limits)) }

    shrunkPaths #::: shrunkStreams #::: shrunkStreamsLessLoops
      .filter(shrunkSp => sp.limits.fold(true)(limit => shrunkSp.stream.size >= limit.min && shrunkSp.stream.size <= limit.max))
      .filter(shrunkSp => shrunkSp.stream != sp.stream || shrunkSp.path != sp.path) //TODO this is a hack
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

  private val loopGen: Gen[Loop] = Gen.sized { size =>
    def go(gLoop: Gen[Loop]): Gen[Loop] = for {
      loop   <- gLoop
      choice <- Gen.choose(0, loop.adjacent.size)
      pair   <- stationaryPairGen
    } yield if (loop.size >= size) loop
            else if (choice == loop.adjacent.size) go(Loop(loop.adjacent :+ Nest(pair)))
            else go(Loop(loop.adjacent.lift(choice).map { nest => loop.adjacent.updated(choice, nest.prepend(pair)) }.get ))


    go(Gen.const(Loop.empty))
  }

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

  private val stationaryPairGen: Gen[Pair[N, P]] =
    arbBool.arbitrary.map(b => if(b) ABPair(StationaryNext, StationaryPrev) else BAPair(StationaryPrev, StationaryNext))

  private def minMax(min: Option[Long], max: Option[Long]): Option[MinMax] = (max, min) match {
    case (None, None)             => None
    case (None, Some(max_))       => Some(MinMax(0,    max_))
    case (Some(min_), None)       => Some(MinMax(min_, Int.MaxValue.toLong))
    case (Some(min_), Some(max_)) => Some(MinMax(min_, max_))
  }

}
