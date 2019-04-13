package util

// Scalacheck
import org.scalacheck.{Arbitrary, Gen}
import zipper.BufferedZipper

// Scala
import scalaz.Monad

// Project
import util.Directions.{N, NP, P}
import util.PropertyFunctions.toWindowBufferOnPath
import zipper.{WindowBuffer, Limit, Unlimited, Size, Bytes}

object Generators {
  type Path = Stream[NP]

  val intStreamGen: Gen[Stream[Int]] = implicitly[Arbitrary[Stream[Int]]].arbitrary

  /**
    * 10% of the time it will be an unbounded buffer regardless of Generator size
    */
  val bufferSizeGen: Gen[Limit] = Gen.sized { size => Gen.const(Bytes(16L * size)) }
    .flatMap { bSize => Gen.choose(0, 9).map { n => if (n == 0) Unlimited else bSize } }


  def bufferGenAtLeast(min: Long): Gen[Limit] = Gen.sized { size =>
    Gen.const(Bytes(16L * size + min))
  }

  def bufferGenNoBiggerThan(max: Long): Gen[Limit] = Gen.sized { size =>
    val realMax = if (max < 0) 0 else max
    val cap = 16L * size
    Gen.const(Bytes(if (cap > realMax) realMax else cap))
  }

  val pathGen: Gen[Stream[NP]] = Gen.listOf(
    Gen.pick(1, List(N, N, P)).flatMap(_.head))
    .flatMap(_.toStream)

  def windowBufferSizeLimitGen[A]()(implicit eva: Gen[A], evsa: Gen[Stream[A]]): Gen[WindowBuffer[A]] = for {
    limit <- Arbitrary.arbInt.arbitrary
    buff  <- windowBufferGen(Size(limit))(eva, evsa)
  } yield buff

  def windowBufferByteLimitGen[A]()(implicit eva: Gen[A], evsa: Gen[Stream[A]]): Gen[WindowBuffer[A]] = for {
    limit <- Arbitrary.arbLong.arbitrary
    buff  <- windowBufferGen(Bytes(limit))(eva, evsa)
  } yield buff

  def windowBufferGen[A](limit: Limit)(implicit eva: Gen[A], evsa: Gen[Stream[A]]): Gen[WindowBuffer[A]] = Gen.sized { size =>
    for {
      a    <- eva
      sa   <- Gen.resize(size, evsa)
      path <- Gen.resize(size, pathGen)
      buff =  toWindowBufferOnPath(a, sa, limit, path)
    } yield buff
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
