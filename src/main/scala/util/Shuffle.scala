package util

import scalaz.{Monad, State}

import scala.Stream.Empty
import scala.util.Random

object Shuffle {
  type Rand[A] = State[Random, A]

  def shuffle[A](s: Stream[A]): Rand[Stream[A]] = shuffleLength(s).map(_._1)

  private[util] def halve[A](s: Stream[A]): (Stream[A], Stream[A]) = s match {
    case Empty    => (Empty, Empty)
    case z #:: zs => halve(zs) match { case (xs, ys) => (z #:: ys, xs) }
  }

  private[util] def shuffleLength[A](s: Stream[A]): Rand[(Stream[A], Int)] = s match {
    case Empty       => Monad[Rand].point((Empty,     0))
    case x #:: Empty => Monad[Rand].point((Stream(x), 1))
    case _ #:: _     => halve(s) match { case (lHalf, rHalf) => for {
      left           <- shuffleLength(lHalf)
      right          <- shuffleLength(rHalf)
      (lSize, rSize) =  (left._2, right._2)
      shuffled       <- riffle(left, right)
    } yield (shuffled, lSize + rSize) }
  }

  private[util] def riffle[A](l: (Stream[A], Int), r: (Stream[A], Int)): Rand[Stream[A]] = (l, r) match {
    case ((xs,       _ ), (Empty,    _ )) => Monad[Rand].point(xs)
    case ((Empty,    _ ), (ys,       _ )) => Monad[Rand].point(ys)
    case ((x #:: xs, nx), (y #:: ys, ny)) => rng(below = nx+ny).flatMap { k =>
      if (k < nx) riffle((xs,       nx-1), (y #:: ys, ny))   map { x #:: _ }
      else        riffle((x #:: xs, nx),   (ys,       ny-1)) map { y #:: _ }
    }
  }

  //[0, below)
  def rng(below: Int): Rand[Int] = for {
    r <- State.get[Random]
    i =  if (below <= 0 ) 1 else below // zeros for bad input
    b =  r.nextInt(i) // throws when i <= zero
  } yield b
}