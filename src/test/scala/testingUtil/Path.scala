package testingUtil

import nest._
import ImplicitClasses.StreamSyntax
import testingUtil.Directions.{Next, Prev, PrevNext}

case class Path private (endAndBack: Stream[PrevNext], ls: Nest[Loop, Loop]) {
  val steps: Stream[PrevNext] =
    (endAndBack.map(List(_)) intersperse
      ls.toStream.map(_.fold(identity, identity)).map(_.steps.toList))
      .flatten

  def removeLoop(index: Int): Path = Path(endAndBack, ls.pluck(index))

  def keepOneLoop(index: Int): Path = {
    val stream = ls.toStream.map(_.merge)
    Path(stream.take(index) #::: (Loop.empty #:: stream.drop(index+1)))
  }

  val noLoops: Path = Path(endAndBack, ls.map(_ => Left((Loop.empty, Loop.empty))))

  //TODO remove tODO fix the zipWithIndex incorrectness that I replaced this with. ugh.
  //  lazy val loopsAndPositions: Vector[(Loop, Int)] = ls.zipWithIndex
  //    .map { case (loop, index) =>
  //      if(index < ls.size) (loop, index)
  //      else (loop, index - (index - ls.size)) }
}

object Path {
  val empty: Path = Path(Nest.empty)

  private def thereAndBack(size: Int): Stream[PrevNext] =
    Stream.fill(size)(Next) #::: Stream.fill(size)(Prev)

  //assumes underlying bounds by nest depth
  def apply(loops: Nest[Loop, Loop]): Path =
    new Path(thereAndBack(loops.depth), loops)

  // not public. assumes loops is even.
  private def apply(loops: Stream[Loop]): Path =
    new Path(thereAndBack(loops.size),
      loops.zip(loops.reverse)
        .take(loops.size/2)
        .reverse
        .foldLeft[Nest[Loop, Loop]](Nest.empty) {
          case (n, (l, r)) => l </: n :/> r
      } )
}