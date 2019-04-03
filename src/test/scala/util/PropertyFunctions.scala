package util

// Scala
import org.github.jamm.MemoryMeter
import scalaz.{Monad, State}
import zipper.BufferedZipper
import scala.collection.immutable.Stream.Empty

// Project
import util.Directions._
import util.Generators.Path

object PropertyFunctions {
  val meter = new MemoryMeter

  trait Direction
  object Forwards  extends Direction
  object Backwards extends Direction

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

  type Counter[A] = State[Int, A]
  def bumpCounter[A](a: A): State[Int, A] =
    State.modify[Int](_ + 1).map(_ => a)

  def zeroCounter[A](a: A): State[Int, A] =
    State.modify[Int](_ => 0).map(_ => a)

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
