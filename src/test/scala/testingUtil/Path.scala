package testingUtil

import ImplicitClasses.StreamSyntax
import testingUtil.Directions.{N, Next, P, Prev, PrevNext}
import testingUtil.Nest.ABPair

case class Path private (endAndBack: Nest[N, P], ls: Nest[Loop, Loop]) {
  val steps: Stream[PrevNext] =
    (endAndBack.toStream.map(_.fold(_ => Next,_ => Prev)).map(List(_)) intersperse
      ls.toStream.map(_.fold(identity, identity)).map(_.toList))
      .flatten

  def removeLoop(index: Int):  Path = Path(endAndBack, ls.pluck(index))
  def keepOneLoop(index: Int): Path = Path(endAndBack, ls.map(_ => Loop.empty).updated(index, ls.lift(index).getOrElse(Loop.empty)))
  def removeAllLoops: Path = Path(endAndBack, ls.map(_ => Loop.empty))
  lazy val loopsAndPositions: Vector[(Loop, Int)] = ls.zipWithIndex
    .map { case (loop, index) =>
      if(index < ls.size) (loop, index)
      else (loop, index - (index - ls.size))}
}
object Path {
  def apply(loops: NestCons[Loop, Loop]): Path =
    new Path(loops.map[N, P](_ => ABPair(Next, Prev)), loops)

  val empty = Path(Vector(Loop.empty))
}