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
