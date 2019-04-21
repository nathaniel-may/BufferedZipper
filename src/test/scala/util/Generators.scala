package util

// Scalacheck
import org.scalacheck.{Arbitrary, Gen}
import zipper.BufferedZipper

// Scala
import scalaz.Monad
import scala.language.higherKinds

// Project
import util.Directions.{N, NP, P}
import util.PropertyFunctions.toWindowBufferOnPath
import zipper.{WindowBuffer, Limit, Unlimited, Size, Bytes}

object Generators {
  type Path = Stream[NP]

  val intStreamGen: Gen[Stream[Int]] = implicitly[Arbitrary[Stream[Int]]].arbitrary
  val uniqueIntStreamGen: Gen[Stream[Int]] = intStreamGen.map(_.distinct)

  val sizeLimitGen: Gen[Limit] = Gen.sized { size => Gen.const(Size(size)) }
  val byteLimitGen: Gen[Limit] = Gen.sized { size => Gen.const(Bytes(16L * size)) }
  val noLimitGen: Gen[Limit] = Gen.const(Unlimited)
  val noBuffer: Gen[Limit] = Gen.const(Size(0))

  /**
    * 10% Unlimited
    * 45% Size(size)
    * 45% Bytes(16L * size)
    */
  val limitGen: Gen[Limit] = Gen.sized { size =>
    Gen.choose(0, 99).flatMap { n =>
      if      (n < 10) noLimitGen
      else if (n < 55) Gen.resize(size, byteLimitGen)
      else             Gen.resize(size, sizeLimitGen) } }

  def bufferGenBytesAtLeast(min: Long): Gen[Limit] = Gen.sized { size =>
    Gen.const(Bytes(16L * size + min))
  }

  def bufferGenSizeAtLeast(min: Int): Gen[Limit] = Gen.sized { size =>
    Gen.const(Size(size + min))
  }

  def bufferGenNoBiggerThan(max: Long): Gen[Limit] = Gen.sized { size =>
    val realMax = if (max < 0) 0 else max
    val cap = 16L * size
    Gen.const(Bytes(if (cap > realMax) realMax else cap))
  }

  val pathGen: Gen[Stream[NP]] = Gen.listOf(
    Gen.pick(1, List(N, N, P)).flatMap(_.head))
    .flatMap(_.toStream)

  def windowBufferGen[A]()(implicit limit: Gen[Limit], eva: Gen[A], evsa: Gen[Stream[A]]): Gen[WindowBuffer[A]] = Gen.sized { size =>
    for {
      a    <- eva
      sa   <- Gen.resize(size, evsa)
      path <- Gen.resize(size, pathGen)
      lim  <- limit
    } yield toWindowBufferOnPath(a, sa, lim, path)
  }

  final case class WithEffect[M[_] : Monad]() {
    private val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def bZipGen[A](buffGen: Gen[Limit], init: A => M[A] = (a: A) => point[A](a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMin[A](1)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    def uniqueBZipGen[A](buffGen: Gen[Limit], init: A => M[A] = (a: A) => point[A](a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      uniqueStreamGen[A](1)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    def bZipGenMin[A](minSize: Int, buffGen: Gen[Limit], init: A => M[A] = (a: A) => point[A](a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMin[A](minSize)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    def bZipGenMax[A](maxSize: Int, buffGen: Gen[Limit], init: A => M[A] = (a: A) => point[A](a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMax[A](maxSize)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    private def uniqueStreamGen[A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[A]] = for {
      s        <- evsa.arbitrary
      nonEmpty <- eva.arbitrary.map(_ #:: s)
      unique   =  nonEmpty.distinct
    } yield unique

    private def streamGenMin[A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[A]] = for {
      s  <- evsa.arbitrary
      s2 <- Gen.pick(2, eva.arbitrary, eva.arbitrary).map(x => x.toStream #::: s)
    } yield s2

    private def streamGenMax[A](maxSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[A]] =
      streamGenMin[A](1).map(_.take(maxSize))

  }
}
