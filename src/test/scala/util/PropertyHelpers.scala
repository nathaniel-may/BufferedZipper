package util

// Scala
import scalaz.Monad

// Project
import BufferedZipperFunctions._
import Generators.Path
import Directions._

object PropertyHelpers {

  // TODO add fail early behavior
  def assertAcrossDirections[M[_] : Monad, A](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => Boolean): M[Boolean] = {
    val syntax = implicitly[Monad[M]].monadSyntax
    import syntax._
    for {
      forwards  <- unzipAndMap(Forwards, bz, f)
      backwards <- unzipAndMap(Backwards, bz, f)
      arb       <- unzipAndMapViaPath(path, bz, f)
    } yield forwards.forall(_ == true) && backwards.forall(_ == true) && arb.forall(_ == true)
  }

  def resultsAcrossDirections[M[_] : Monad, A, B](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => B): M[(List[B], List[B], List[B])] = {
    val syntax = implicitly[Monad[M]].monadSyntax
    import syntax._
    for {
      forwards  <- unzipAndMap(Forwards, bz, f)
      backwards <- unzipAndMap(Backwards, bz, f)
      arb       <- unzipAndMapViaPath(path, bz, f)
    } yield (forwards, backwards, arb)
  }

  def move[M[_] : Monad, A](path: Path, bz: BufferedZipper[M, A]): M[BufferedZipper[M, A]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, A], steps: Stream[PrevNext]): M[BufferedZipper[M, A]] = steps match {
      case p #:: ps => (p match {case _: N => z.next; case _: P => z.prev})
        .fold(go(z, ps)) { mbz => mbz.flatMap(zShift => go(zShift, ps)) }
      case Stream.Empty => point(z)
    }

    go(bz, path)
  }

}
