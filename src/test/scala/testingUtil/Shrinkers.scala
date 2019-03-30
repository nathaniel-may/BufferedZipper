package testingUtil

import org.scalacheck.Shrink
import util.Generators.UniqueStream

object Shrinkers {

  def shrinkUniqueStream[T]: Shrink[UniqueStream[T]] = Shrink[UniqueStream[T]] { input =>
    def go(in: Stream[T], out: Stream[UniqueStream[T]]): Stream[UniqueStream[T]] = in match {
      case Stream.Empty => out
      case _ #:: xs     => go(xs, UniqueStream(xs) #:: out)
    }

    go(input.s, Stream())
  }
}
