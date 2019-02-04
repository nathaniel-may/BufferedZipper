package testingUtil

import Directions._

case class Loop(adjacent: Vector[Nest[N, P]]) {
  lazy val nReach: Int = adjacent.map(_.aDepth).max
  lazy val pReach: Int = adjacent.map(_.bDepth).max
  lazy val size:   Int = adjacent.map(_.depth).sum
  lazy val steps: Stream[Stationary] = toStream

  def shrinkNReachTo(reach: Int): Loop ={
    def go(l: Loop): Loop = {
      if(l.nReach <= reach) l
      else go(l.shrinkNReach)
    }

    go(this)
  }

  def shrinkNReach: Loop = {
    def go(l: Loop): Loop = {
      if (l.nReach < this.nReach || this.nReach <= 0) l
      else {
        go(Loop(adjacent.map { nps =>
          if(nps.aDepth < this.nReach) nps
          else nps match {
            case NestCons(_, inner) => inner
            case _              => nps
          }
        }))
      }
    }

    go(this)
  }

  def toStream: Stream[Stationary] =
    adjacent.map(x =>
      x.toStream.map(
        _.fold[Stationary](_ => StationaryNext, _ => StationaryPrev)))
      .reduce(_ #::: _)

  def toList: List[Stationary] =
    adjacent.map(
      _.toList.map(
        _.fold[Stationary](_ => StationaryNext, _ => StationaryPrev)))
    .reduce(_ ::: _)
}

object Loop {
  val empty = Loop(Vector())
}
