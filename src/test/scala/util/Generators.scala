package util

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.{arbBool, arbInt}
import BufferTypes._
import scalaz.{Monad, Zipper}
import testingUtil.Arbitrarily.{StreamAndPath, minMax, stationaryPairGen}
import testingUtil.Directions.{N, P, StationaryNext, StationaryPrev}
import testingUtil.{Loop, Path}

object Generators {

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

  private val stationaryPairGen: Gen[Pair[N, P]] =
    arbBool.arbitrary.map(b => if(b) ABPair(StationaryNext, StationaryPrev) else BAPair(StationaryPrev, StationaryNext))


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

}
