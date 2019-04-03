package util

// Scala
import org.github.jamm.MemoryMeter
import scalaz.{Monad, State}
import zipper.BufferedZipper

// Project
import util.Directions._
import util.Generators.Path

object PropertyFunctions {
  type Counter[A] = State[Int, A]
  val meter = new MemoryMeter

  def bumpCounter[A](a: A): State[Int, A] =
    State.modify[Int](_ + 1).map(_ => a)

  def zeroCounter[A](a: A): State[Int, A] =
    State.modify[Int](_ => 0).map(_ => a)

  def measureBufferContents[M[_]: Monad, A](bs: BufferedZipper[M, A]): Long =
    bs.buffer.toList.map(meter.measureDeep).sum - meter.measureDeep(bs.buffer.focus)

  def assertOnPath[M[_] : Monad, A](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => Boolean): M[Boolean] = {
    val syntax = implicitly[Monad[M]].monadSyntax
    import syntax._
    resultsOnPath(bz, path, f).map(_.forall(identity))
  }

  def resultsOnPath[M[_] : Monad, A, B](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => B): M[List[B]] = {
    val syntax = implicitly[Monad[M]].monadSyntax
    import syntax._
    resultsAndPathTaken(bz, path, f).map(_._1)
  }

  def resultsAndPathTaken[M[_] : Monad, A, B](zipper: BufferedZipper[M, A], path: Stream[NP], f: BufferedZipper[M, A] => B): M[(List[B], Path)] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    // if the path walks off the zipper it keeps evaluating till it walks back on, but doesn't record the out of range
    def go(z: BufferedZipper[M, A], steps: Stream[NP], l: M[List[B]], stepsTaken: Stream[NP]): M[(List[B], Stream[NP])] = steps match {
      case N #:: ps => z.next.fold(go(z, ps, l, stepsTaken)) { mbz =>
        mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _), N #:: stepsTaken)) }
      case P #:: ps => z.prev.fold(go(z, ps, l, stepsTaken)) { mbz =>
        mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _), P #:: stepsTaken)) }
      case Stream.Empty => l.map(x => (x.reverse, stepsTaken.reverse))
    }

    go(zipper, path, point(List(f(zipper))), Stream())
  }

  def move[M[_] : Monad, A](path: Path, bz: BufferedZipper[M, A]): M[BufferedZipper[M, A]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, A], steps: Stream[NP]): M[BufferedZipper[M, A]] = steps match {
      case N #:: ps => z.next.fold(go(z, ps)) { mbz =>
        mbz.flatMap(zShift => go(zShift, ps)) }
      case P #:: ps => z.prev.fold(go(z, ps)) { mbz =>
        mbz.flatMap(zShift => go(zShift, ps)) }
      case Stream.Empty => point(z)
    }

    go(bz, path)
  }
}
