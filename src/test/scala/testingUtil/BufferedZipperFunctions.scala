package testingUtil

import scalaz.Monad
import util.BufferedZipper
import org.github.jamm.MemoryMeter

object BufferedZipperFunctions {
  val meter = new MemoryMeter

  trait Direction
  object Forwards  extends Direction
  object Backwards extends Direction

  def bufferContains[M[_]: Monad, A](bs: BufferedZipper[M, A], elem: A): Boolean =
    bs.buffer.v.contains(Some(elem))

  def measureBufferContents[M[_]: Monad, A](bs: BufferedZipper[M, A]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)

  //TODO add to type?
  def toList[M[_] : Monad, A](dir: Direction, in: BufferedZipper[M, A]): M[List[A]] =
    unzipAndMap[M, A, A](dir, in, bs => bs.focus)

  def goToEnd[M[_]: Monad, A](bz: BufferedZipper[M,A]): M[BufferedZipper[M,A]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    bz.next match {
      case None      => point(bz)
      case Some(mbz) => mbz.flatMap(goToEnd(_))
    }
  }

  def unzipToListOfBufferSize[M[_]: Monad, A](dir: Direction, in: BufferedZipper[M, A]): M[List[Long]] =
    unzipAndMap[M, A, Long](dir, in, bs => measureBufferContents(bs))

  def unzipAndMap[M[_] : Monad, A, B](dir: Direction, in: BufferedZipper[M, A], f:BufferedZipper[M, A] => B) : M[List[B]] = dir match {
    case Forwards  => unzipAndMapForwards (in, f)
    case Backwards => unzipAndMapBackwards(in, f)
  }

  private def unzipAndMapForwards[M[_] : Monad, A, B](in: BufferedZipper[M, A], f:BufferedZipper[M, A] => B) : M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, A], l: M[List[B]]): M[List[B]] =
      z.next.fold(l.map(f(z) :: _))(mbz => mbz.flatMap(zNext =>
        go(zNext, l.map(f(z) :: _))))

    go(in, point(List())).map(_.reverse)
  }

  private def unzipAndMapBackwards[M[_]: Monad, A, B](in: BufferedZipper[M, A], f: BufferedZipper[M, A] => B): M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(z: BufferedZipper[M, A], l: M[List[B]]): M[List[B]] = z.prev match {
      case Some(mPrev) => mPrev.flatMap(prev => go(prev, l.map(f(z) :: _)))
      case None        => l.map(f(z) :: _)
    }

    // stops right before it returns None
    def goToEnd(z: BufferedZipper[M, A]): M[BufferedZipper[M, A]] =
      z.next.fold(point(z))(_.flatMap(goToEnd))

    goToEnd(in).flatMap(x => go(x, point(List())))
  }
}
