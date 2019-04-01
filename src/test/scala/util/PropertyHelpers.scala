package util

// Scala
import scalaz.Monad

// Project
import BufferedZipperFunctions._
import Generators.Path
import Directions._

object PropertyHelpers {

  // TODO add fail early behavior
  def assertOnPath[M[_] : Monad, A](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => Boolean): M[Boolean] = {
    val syntax = implicitly[Monad[M]].monadSyntax
    import syntax._
    unzipAndMapViaPath(path, bz, f).map(_.forall(identity))
  }

  def resultsOnPath[M[_] : Monad, A, B](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => B): M[List[B]] =
    unzipAndMapViaPath(path, bz, f) //TODO rename to resultsOnPath

  def resultsOnExactPath[M[_] : Monad, A, B](zipper: BufferedZipper[M, A], path: Stream[NP], f: BufferedZipper[M, A] => B): M[(List[B], Path)] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    // if the path walks off the zipper it keeps evaluating till it walks back on
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
