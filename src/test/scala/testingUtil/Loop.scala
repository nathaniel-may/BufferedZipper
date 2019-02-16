package testingUtil

import nest._
import Directions._

case class Loop private (steps: Stream[Stationary], stops: Stream[Int]) {
  lazy val size:   Int = steps.size
}

object Loop {
  val empty = new Loop(Stream(), Stream())

  /**
    * list of relative indexes this path will visit
    *
    * example:
    * Stream(1, 2, 3)  => Loop(Stream(N, N, N, P, P, P))
    * Stream(1, 3, -1) => Loop(Stream(N, N, N, P, P, P, P, N))
    * Stream(0)        => Loop(Stream())
    */
  def apply(stops: Stream[Int]): Loop = {
    Loop((stops #::: Stream(0)).foldLeft[(Stream[Stationary], Int)]((Stream(), 0)) {
      case ((steps, pos), stop) =>
        if(stop-pos >= 0)
          (steps #::: Stream.fill(stop-pos)(StationaryNext), stop)
        else
          (steps #::: Stream.fill(-1*(stop-pos))(StationaryPrev), stop)
    }._1, stops)
  }
}
