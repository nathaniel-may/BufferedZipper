package testingUtil

import org.scalacheck.{Arbitrary, Gen}

object Arbitrarily {

  case class BufferSize(max: Option[Long])
  case class LimitedBufferSize(max: Long)
  case class NonZeroBufferSize(max: Option[Long])

  case class StreamAtLeast2[A](wrapped: Stream[A])
  case class UniqueStreamAtLeast1[A](wrapped: Stream[A])
  case class Path(wrapped: Stream[Boolean])

  val bufferSizeGen: Gen[BufferSize] = Gen.sized { size =>
    BufferSize(if (size <= 0) None else if(size == 1) Some(Long.MaxValue) else Some(16 * (size - 2))) }

  val limitedBufferSizeGen: Gen[LimitedBufferSize] = Gen.sized { size =>
    LimitedBufferSize(if (size <= 0) Long.MaxValue else 16 * (size - 1)) }

  val nonZeroBufferSizeGen: Gen[NonZeroBufferSize] = Gen.sized { size =>
    NonZeroBufferSize(if (size <= 0) None else if(size == 1) Some(Long.MaxValue) else Some(16 * (size - 1))) }

  implicit val aBufferSize:        Arbitrary[BufferSize]        = Arbitrary(bufferSizeGen)
  implicit val aLimitedBufferSize: Arbitrary[LimitedBufferSize] = Arbitrary(limitedBufferSizeGen)
  implicit val aNonZeroBufferSize: Arbitrary[NonZeroBufferSize] = Arbitrary(nonZeroBufferSizeGen)

  implicit val aUniqueStreamAtLeast1: Arbitrary[UniqueStreamAtLeast1[Int]] =
    Arbitrary(Gen.atLeastOne(-20 to 20).map(seq => UniqueStreamAtLeast1(seq.toStream)))

  implicit val aStreamAtLeast2: Arbitrary[StreamAtLeast2[Int]] = Arbitrary((for {
    l1 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
    l2 <- Gen.nonEmptyListOf[Int](Gen.choose(Int.MinValue, Int.MaxValue))
  } yield l1 ::: l2).map(l => StreamAtLeast2(l.toStream)))

  implicit val aPath: Arbitrary[Path] = Arbitrary(pathGenOf(50))

  private def pathGenOf(length: Int): Gen[Path] = {
    def go(len: Int, path: Gen[Stream[Boolean]]): Gen[Stream[Boolean]] =
      if(len == 0) path
      else go(len-1, path.flatMap(s => ttfGen.map(_ #:: s)))

    go(length, ttfGen.map(Stream(_))).map(Path)
  }

  private val ttfGen: Gen[Boolean] =
    Gen.pick(1, List(true, true, false)).map(_.head)

}
