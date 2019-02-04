package testingUtil

import ImplicitClasses.StreamSyntax
import testingUtil.Directions.{N, Next, P, Prev, PrevNext}

case class Path private (endAndBack: Nest[N, P], ls: Nest[Loop, Loop]) {
  val steps: Stream[PrevNext] =
    (endAndBack.toStream.map(_.fold(_ => Next,_ => Prev)).map(List(_)) intersperse
      ls.toStream.map(_.fold(identity, identity)).map(_.toList))
      .flatten

  def removeLoop(index: Int): Path = Path(endAndBack, ls.pluck(index))

  //nests don't make this particularly easy
  def keepOneLoop(index: Int): Path = {
    val emptyPair = ABPair(Loop.empty, Loop.empty)
    val (thereOrBack, nestIndex) = if (index < ls.size) (Next, index)
                                   else (Prev, (ls.size - 1) - (index - (ls.size - 1)))
    val emptyLoops = ls.map(_ => emptyPair)
    val oneLoopPair = (thereOrBack, ls.lift(nestIndex).getOrElse(emptyPair)) match {
      case (_: N, ABPair(a, _)) => ABPair(a,          Loop.empty)
      case (_: P, ABPair(_, b)) => ABPair(Loop.empty, b)
      case (_: N, BAPair(b, _)) => BAPair(b,          Loop.empty)
      case (_: P, BAPair(_, a)) => BAPair(Loop.empty, a)
    }

    Path(
      endAndBack,
      emptyLoops.take(nestIndex)
        .append(oneLoopPair)
        .append(emptyLoops.drop(nestIndex + 1))
    )

  }

  def noLoops: Path = Path(endAndBack, ls.map(_ => ABPair(Loop.empty, Loop.empty)))

  //TODO remove tODO fix the zipWithIndex incorrectness that I replaced this with. ugh.
//  lazy val loopsAndPositions: Vector[(Loop, Int)] = ls.zipWithIndex
//    .map { case (loop, index) =>
//      if(index < ls.size) (loop, index)
//      else (loop, index - (index - ls.size)) }
}

object Path {
  def apply(loops: Nest[Loop, Loop]): Path =
    new Path(loops.map[N, P](_ => ABPair(Next, Prev)), loops)

  val empty: Path = Path(EmptyNest)
}