package util

// Scalacheck
import org.scalacheck.{Arbitrary, Gen}
import zipper.BufferedZipper

// Scala
import scalaz.Monad

// Project
import util.Directions.{N, NP, P}

object Generators {
  type Path = Stream[NP]

  final case class BufferSize(max: Option[Long])

  val intStreamGen: Gen[Stream[Int]] = implicitly[Arbitrary[Stream[Int]]].arbitrary

  /**
    * 10% of the time it will be an unbounded buffer regardless of Generator size
    */
  val bufferSizeGen: Gen[BufferSize] = Gen.sized { size => Gen.const(BufferSize(Some(16L * size))) }
    .flatMap { bSize => Gen.choose(0,9).map { n => if (n == 0) BufferSize(None) else bSize } }


  def bufferGenAtLeast(min: Long): Gen[BufferSize] = Gen.sized { size =>
    Gen.const(BufferSize(Some(16L * size + min)))
  }

  def bufferGenNoBiggerThan(max: Long): Gen[BufferSize] = Gen.sized { size =>
    val realMax = if (max < 0) 0 else max
    Gen.const(BufferSize(Some(16L * size).map(cap => if (cap > realMax) realMax else cap)))
  }

  def bZipGen[M[_]: Monad, A](buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMin[M, A](1)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

  def bZipGenMin[M[_]: Monad, A](minSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMin[M, A](minSize)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

  def bZipGenMax[M[_]: Monad, A](maxSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMax[M, A](maxSize)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

  case class UniqueStream[T](s: Stream[T])
  private lazy val largeListOfInts = (0 to 300000).toList
  val uniqueIntStreamGen: Gen[UniqueStream[Int]] = Gen.sized { size =>
    Gen.pick(size, largeListOfInts).flatMap(x => UniqueStream(x.toStream))
  }

  val pathGen: Gen[Stream[NP]] = Gen.listOf(
    Gen.pick(1, List(N, N, P)).flatMap(_.head))
      .flatMap(_.toStream)

  private def streamGenMin[M[_]: Monad, A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] = for {
    s  <- evsa.arbitrary
    s2 <- Gen.pick(2, eva.arbitrary, eva.arbitrary).map(x => x.toStream #::: s)
    ms <- s2.map { implicitly[Monad[M]].point(_) }
  } yield ms

  private def streamGenMax[M[_]: Monad, A](maxSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] = for {
    s  <- evsa.arbitrary
    s2 =  s.take(maxSize)
    ms <- s2.map { implicitly[Monad[M]].point(_) }
  } yield ms

}
