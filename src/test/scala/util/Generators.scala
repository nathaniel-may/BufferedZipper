package util

// Scalacheck
import org.scalacheck.{Arbitrary, Gen}

// Scala
import scalaz.Monad

// Project
import BufferTypes._
import Directions.{Next, Prev, PrevNext}

object Generators {

  type Path = Stream[PrevNext]

  val intStreamGen: Gen[Stream[Int]] = implicitly[Arbitrary[Stream[Int]]].arbitrary

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

  def bZipGen[M[_]: Monad, A](buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMin[M, A](1)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, Some(buff.cap)).get } }

  def bZipGenMin[M[_]: Monad, A](minSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMin[M, A](minSize)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, Some(buff.cap)).get } }

  def bZipGenMax[M[_]: Monad, A](maxSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMax[M, A](maxSize)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, Some(buff.cap)).get } }

  private lazy val largeListOfInts = (0 to 300000).toList
  val uniqueIntStreamGen: Gen[Stream[Int]] = Gen.sized { size =>
    Gen.pick(size, largeListOfInts).flatMap(_.toStream)
  }

  val pathGen: Gen[Stream[PrevNext]] = Gen.listOf(
    Gen.pick(1, List(Next, Next, Prev)).flatMap(_.head))
      .flatMap(_.toStream)

  def streamGenMin[M[_]: Monad, A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] = for {
    s  <- evsa.arbitrary
    s2 <- Gen.pick(2, eva.arbitrary, eva.arbitrary).map(x => x.toStream #::: s)
    ms <- s2.map { implicitly[Monad[M]].point(_) }
  } yield ms

  def streamGenMax[M[_]: Monad, A](maxSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] = for {
    s  <- evsa.arbitrary
    s2 =  s.take(maxSize)
    ms <- s2.map { implicitly[Monad[M]].point(_) }
  } yield ms

}
