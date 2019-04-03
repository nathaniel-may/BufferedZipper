package util

import org.github.jamm.MemoryMeter
import scalaz.Monad
import zipper.BufferedZipper
import Directions.{N, NP, P}

import scala.collection.immutable.Stream.Empty

object BufferedZipperFunctions {
  val meter = new MemoryMeter

  trait Direction
  object Forwards  extends Direction
  object Backwards extends Direction

  def measureBufferContents[M[_]: Monad, A](bs: BufferedZipper[M, A]): Long =
    bs.buffer.toList.map(meter.measureDeep).sum - meter.measureDeep(bs.buffer.focus)

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

  private def mapAlong[M[_] : Monad, A, B](path: Stream[NP])(in: BufferedZipper[M, A], f: A => B) : M[List[B]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    def go(p: Stream[NP], z: BufferedZipper[M, A], l: M[List[B]]): M[List[B]] = p match {
      case Empty            => l
      case N #:: steps => z.next.fold(l)(mn => mn.flatMap(n => go(steps, n, l.map(f(n.focus) :: _))))
      case P #:: steps => z.prev.fold(l)(mp => mp.flatMap(p => go(steps, p, l.map(f(p.focus) :: _))))
    }

    go(path, in, point(List())).map(_.reverse)
  }
}
