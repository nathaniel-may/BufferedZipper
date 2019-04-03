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
    .flatMap { bSize => Gen.choose(0, 9).map { n => if (n == 0) BufferSize(None) else bSize } }


  def bufferGenAtLeast(min: Long): Gen[BufferSize] = Gen.sized { size =>
    Gen.const(BufferSize(Some(16L * size + min)))
  }

  def bufferGenNoBiggerThan(max: Long): Gen[BufferSize] = Gen.sized { size =>
    val realMax = if (max < 0) 0 else max
    Gen.const(BufferSize(Some(16L * size).map(cap => if (cap > realMax) realMax else cap)))
  }

  val pathGen: Gen[Stream[NP]] = Gen.listOf(
    Gen.pick(1, List(N, N, P)).flatMap(_.head))
    .flatMap(_.toStream)

  final case class WithEffect[M[_] : Monad]() {
    private val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def bZipGen[A](buffGen: Gen[BufferSize], init: A => M[A] = (a: A) => point[A](a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMin[A](1)(evsa, eva)
        .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

    def uniqueBZipGen[A](buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      uniqueStreamGen[A](1)(evsa, eva)
        .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

    def bZipGenMin[A](minSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMin[A](minSize)(evsa, eva)
        .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

    def bZipGenMax[A](maxSize: Int, buffGen: Gen[BufferSize])(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMax[A](maxSize)(evsa, eva)
        .flatMap { sm => buffGen.map { buff => BufferedZipper[M, A](sm, buff.max).get } }

    private def uniqueStreamGen[A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] = for {
      s        <- evsa.arbitrary
      nonEmpty <- eva.arbitrary.map(_ #:: s)
      unique   =  nonEmpty.distinct
      ms       <- unique.map { point(_) }
    } yield ms

    private def streamGenMin[A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] = for {
      s  <- evsa.arbitrary
      s2 <- Gen.pick(2, eva.arbitrary, eva.arbitrary).map(x => x.toStream #::: s)
      ms <- s2.map { point(_) }
    } yield ms

    private def streamGenMax[A](maxSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[M[A]]] =
      streamGenMin[A](1).map(_.take(maxSize))

  }
}
