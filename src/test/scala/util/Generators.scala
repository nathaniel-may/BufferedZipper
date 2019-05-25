package util

// Scalacheck
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.cats.implicits._

// Scala
import cats.Monad
import cats.implicits._

// Project
import zipper.BufferedZipper
import util.Directions.{N, NP, P}
import util.PropertyFunctions.toWindowBufferOnPath
import zipper.{WindowBuffer, Limit, Unlimited, SizeLimit, ByteLimit}

object Generators {
  type Path = Stream[NP]

  //implicit lazy val GenApplicative: Applicative[Gen] = implicitly[Applicative[Gen]]

  val intStreamGen: Gen[Stream[Int]] = implicitly[Arbitrary[Stream[Int]]].arbitrary
  val uniqueIntStreamGen: Gen[Stream[Int]] = intStreamGen.map(_.distinct)

  val sizeLimitGen: Gen[SizeLimit] = Gen.sized { size => Gen.const(SizeLimit(size)) }
  val byteLimitGen: Gen[ByteLimit] = Gen.sized { size => Gen.const(ByteLimit(16L * size)) }
  val noLimitGen:   Gen[Limit]     = Gen.const(Unlimited)
  val noBuffer:     Gen[SizeLimit] = Gen.const(SizeLimit(0))

  class Inheritance
  class Subtype1 extends Inheritance
  class Subtype2 extends Subtype1
  class SubtypeA extends Inheritance
  class SubtypeB extends SubtypeA

  val inheritanceGen: Gen[Inheritance] = Gen.choose(0, 50).flatMap { n =>
    if      (n < 10) Gen.const(new Inheritance)
    else if (n < 20) Gen.const(new Subtype1)
    else if (n < 30) Gen.const(new Subtype2)
    else if (n < 40) Gen.const(new SubtypeA)
    else             Gen.const(new SubtypeB) }

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

  def byteLimitAtLeast(min: Long): Gen[Limit] = byteLimitGen.map { lim =>
    if (lim.max < min) ByteLimit(min) else lim }

  def sizeLimitAtLeast(min: Int): Gen[Limit] = sizeLimitGen.map { lim =>
    if (lim.max < min) SizeLimit(min) else lim }

  def bufferGenNoBiggerThan(max: Long): Gen[Limit] = Gen.sized { size =>
    val realMax = if (max < 0) 0 else max
    val cap = 16L * size
    Gen.const(ByteLimit(if (cap > realMax) realMax else cap))
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

  final case class WithEffect[M[_]]()(implicit m: Monad[M]) {

    def bZipGen[A](buffGen: Gen[Limit], init: A => M[A] = (a: A) => m.point(a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMin[A](1)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    def uniqueBZipGen[A](buffGen: Gen[Limit], init: A => M[A] = (a: A) => m.point(a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      uniqueStreamGen[A](1)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    def bZipGenMin[A](minSize: Int, buffGen: Gen[Limit], init: A => M[A] = (a: A) => m.point(a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMin[A](minSize)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    def bZipGenMax[A](maxSize: Int, buffGen: Gen[Limit], init: A => M[A] = (a: A) => m.point(a))(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[M[BufferedZipper[M, A]]] =
      streamGenMax[A](maxSize)(evsa, eva)
        .map(_.map(init))
        .flatMap { sm => buffGen.map { limits => BufferedZipper[M, A](sm, limits).get } }

    private def uniqueStreamGen[A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[A]] =
      streamGenMin(minSize).map(_.distinct)

    private def streamGenMin[A](minSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[A]] = for {
      min      <- Stream.fill(minSize)(eva.arbitrary).sequence
      stream   <- evsa.arbitrary
    } yield min #::: stream

    private def streamGenMax[A](maxSize: Int)(implicit evsa: Arbitrary[Stream[A]], eva: Arbitrary[A]): Gen[Stream[A]] =
      streamGenMin[A](1).map(_.take(maxSize))
  }
}
