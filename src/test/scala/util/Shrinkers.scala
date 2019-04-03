package util

import org.scalacheck.Shrink
import zipper.Generators.UniqueStream

object Shrinkers {

  def shrinkUniqueStream[T: Shrink]: Shrink[UniqueStream[T]] = Shrink[UniqueStream[T]] { input =>
    def isUnique[A](s: Stream[A]): Boolean =
      s.groupBy(identity).forall(_._2.size == 1)

    Shrink.shrink(input.s).filter(isUnique(_)).map(UniqueStream(_))
//
//    def go(in: Stream[T], out: Stream[UniqueStream[T]]): Stream[UniqueStream[T]] = in match {
//      case Stream.Empty => out
//      case _ #:: xs     => go(xs, UniqueStream(xs) #:: out)
//    }
//
//    go(input.s, Stream())
  }
}
