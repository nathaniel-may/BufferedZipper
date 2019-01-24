package testingUtil

import org.scalacheck.{Arbitrary, Gen}

object Arbitrarily {

  case class NonNegLong(wrapped: Long)
  case class StreamAtLeast2[A](wrapped: Stream[A])
  case class UniqueStreamAtLeast1[A](wrapped: Stream[A])

  implicit val aUniqueStreamAtLeast1: Arbitrary[UniqueStreamAtLeast1[Int]] =
    Arbitrary(Gen.atLeastOne(-20 to 20).map(seq => UniqueStreamAtLeast1(seq.toStream)))

  implicit val aStreamAtLeast2: Arbitrary[StreamAtLeast2[Int]] = Arbitrary((for {
    l1 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
    l2 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
  } yield l1 ::: l2).map(l => StreamAtLeast2(l.toStream)))

  implicit val aPositiveLong: Arbitrary[NonNegLong] =
    Arbitrary(Gen.choose(0, Long.MaxValue).map(NonNegLong))

}
