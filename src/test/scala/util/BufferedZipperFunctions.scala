package util

import org.github.jamm.MemoryMeter
import scalaz.Monad
import Directions.{N, P, PrevNext}

import scala.collection.immutable.Stream.Empty

object BufferedZipperFunctions {
  val meter = new MemoryMeter

  trait Direction
  object Forwards  extends Direction
  object Backwards extends Direction

  def bufferContains[M[_]: Monad, A](bs: BufferedZipper[M, A], elem: A): Boolean =
    bs.buffer.v.contains(Some(elem))

  def measureBufferContents[M[_]: Monad, A](bs: BufferedZipper[M, A]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)

  def unzipAndMapViaPath[M[_] : Monad, A, B](zipper: BufferedZipper[M, A], f: BufferedZipper[M, A] => B, path: Stream[PrevNext]): M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    // if the path walks off the zipper it keeps evaluating till it walks back on
    def go(z: BufferedZipper[M, A], steps: Stream[PrevNext], l: M[List[B]]): M[List[B]] = steps match {
      case p #:: ps => (p match {case _: N => z.next; case _: P => z.prev})
        .fold(go(z, ps, l)) { mbz => mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _))) }
      case Empty => l
    }

    go(zipper, path, point(List()))
  }

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

  private def mapAlong[M[_] : Monad, A, B](path: Stream[PrevNext])(in: BufferedZipper[M, A], f: A => B) : M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(p: Stream[PrevNext], z: BufferedZipper[M, A], l: M[List[B]]): M[List[B]] = p match {
      case Empty            => l
      case (_: N) #:: steps => z.next.fold(l)(mn => mn.flatMap(n => go(steps, n, l.map(f(n.focus) :: _))))
      case (_: P) #:: steps => z.prev.fold(l)(mp => mp.flatMap(p => go(steps, p, l.map(f(p.focus) :: _))))
    }

    go(path, in, point(List())).map(_.reverse)
  }

}
