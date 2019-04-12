package util

// ScalaCheck
import org.scalacheck.Shrink

// Scala
import scalaz.Scalaz.Id

// Project
import zipper.BufferedZipper


object Shrinkers {

  final case class UniqueBZip[A](bz: BufferedZipper[Id, A])

  def shrinkUniqueBZip[T : Shrink]: Shrink[UniqueBZip[T]] = Shrink[UniqueBZip[T]] { input =>
    def isUnique[A](s: Stream[A]): Boolean =
      s.groupBy(identity).forall(_._2.size == 1)

    Shrink.shrink(input.bz.toStream)
      .filter(isUnique(_))
      .filter(_.nonEmpty)
      .map { s => UniqueBZip(BufferedZipper(s, input.bz.buffer.limits).get) }
  }
}
