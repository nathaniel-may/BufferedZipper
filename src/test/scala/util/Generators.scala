package util

import org.scalacheck.{Arbitrary, Gen}
import scalaz.Monad
import scalaz.effect.IO
import scalaz.Id
import BufferTypes._
import Directions.{Next, Prev, PrevNext}

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

  def bZipGen[M[_]: Monad, A](buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMin[M, A](1)(implicitly[Monad[M]], evsa.arbitrary, eva.arbitrary)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, Some(buff.cap)).get } }

  def bZipGenMin[M[_]: Monad, A](minSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMin[M, A](minSize)(implicitly[Monad[M]], evsa.arbitrary, eva.arbitrary)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, Some(buff.cap)).get } }

  def bZipGenMax[M[_]: Monad, A](maxSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Gen[Stream[A]], eva: Gen[A]): Gen[M[BufferedZipper[M, A]]] =
    streamGenMax[M, A](maxSize)(implicitly[Monad[M]], evsa, eva)
      .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, Some(buff.cap)).get } }

  private def streamGenMin[M[_]: Monad, A](minSize: Int)(implicit evsa: Gen[Stream[A]], eva: Gen[A]): Gen[Stream[M[A]]] =
    evsa.flatMap { s => Gen.pick(2, eva, eva).map(x => x.toStream #::: s) }
      .map(_.map(a => implicitly[Monad[M]].point(a)))

  private def streamGenMax[M[_]: Monad, A](maxSize: Int)(implicit evsa: Gen[Stream[A]], eva: Gen[A]): Gen[Stream[M[A]]] =
    evsa.flatMap(s => Gen.pick(1, eva, eva).map { _.head #:: s.take(maxSize) } ).map(_.map(a => implicitly[Monad[M]].point(a)))

  private def pathGen(streamLength: Int): Gen[Stream[PrevNext]] = Gen.sized { size =>
    Gen.pick(size, List(Next, Next, Prev)).map(_.toStream)
  }

}
