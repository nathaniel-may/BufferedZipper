package util

// Scala
import scalaz.Monad

// Project
import BufferedZipperFunctions._
import Generators.Path

object PropertyHelpers {

  def assertAcrossDirections[M[_] : Monad, A](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => Boolean): M[Boolean] = {
    val syntax = implicitly[Monad[M]].monadSyntax
    import syntax._
    for {
      forwards  <- unzipAndMap(Forwards, bz, f)
      backwards <- unzipAndMap(Backwards, bz, f)
      arb       <- unzipAndMapViaPath(bz, f, path)
    } yield forwards.forall(_ == true) && backwards.forall(_ == true) && arb.forall(_ == true)
  }

}
