package util

// Scala
import cats.Monad
import cats.data.{OptionT, State}
import cats.implicits.{toFunctorOps, toFlatMapOps}
import zipper.{BufferedZipper, HasRight, NoRight, HasLeft, NoLeft, WindowBuffer}
import scala.language.higherKinds

// Java
import org.github.jamm.MemoryMeter

// Project
import util.Directions._
import util.Generators.Path
import zipper.Limit

object PropertyFunctions {
  type Id[A] = A
  type Counter[A] = State[Int, A]
  val meter = new MemoryMeter

  def bumpCounter[A](a: A): State[Int, A] =
    State.modify[Int](_ + 1).map(_ => a)

  def zeroCounter[A](a: A): State[Int, A] =
    State.modify[Int](_ => 0).map(_ => a)

  def measureBufferContents[A](buff: WindowBuffer[A]): Long =
    buff.toList.map(meter.measureDeep).sum - meter.measureDeep(buff.focus)

  def assertOnPath[M[_] : Monad, A](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => Boolean): M[Boolean] =
    resultsOnPath(bz, path, f).map(_.forall(identity))

  def resultsOnPath[M[_] : Monad, A, B](bz: BufferedZipper[M, A], path: Path, f: BufferedZipper[M, A] => B): M[List[B]] =
    resultsAndPathTaken(bz, path, f).map(_._1)

  def resultsAndPathTaken[M[_], A, B](zipper: BufferedZipper[M, A], path: Stream[NP], f: BufferedZipper[M, A] => B)(implicit m: Monad[M]): M[(List[B], Path)] = {
    // if the path walks off the zipper it keeps evaluating till it walks back on, but doesn't record the out of range
    def go(z: BufferedZipper[M, A], steps: Stream[NP], l: M[List[B]], stepsTaken: Stream[NP]): M[(List[B], Stream[NP])] = steps match {
      case N #:: ps => z.next.fold(go(z, ps, l, stepsTaken)) { mbz =>
        mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _), N #:: stepsTaken)) }
      case P #:: ps => z.prev.fold(go(z, ps, l, stepsTaken)) { mbz =>
        mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _), P #:: stepsTaken)) }
      case Stream.Empty => l.map(x => (x.reverse, stepsTaken.reverse))
    }

    go(zipper, path, m.point(List(f(zipper))), Stream())
  }

  def move[M[_], A](path: Path, bz: BufferedZipper[M, A])(implicit m: Monad[M]): M[BufferedZipper[M, A]] = {
    def go(z: BufferedZipper[M, A], steps: Stream[NP]): M[BufferedZipper[M, A]] = steps match {
      case N #:: nps => z.next.fold(go(z, nps)) { mbz =>
        mbz.flatMap(zShift => go(zShift, nps)) }
      case P #:: nps => z.prev.fold(go(z, nps)) { mbz =>
        mbz.flatMap(zShift => go(zShift, nps)) }
      case Stream.Empty => m.point(z)
    }

    go(bz, path)
  }

  def moveT[M[_], A](path: Path, bz: BufferedZipper[M, A])(implicit m: Monad[M]): OptionT[M, BufferedZipper[M, A]] = {
    def go(z: BufferedZipper[M, A], steps: Stream[NP]): M[Option[BufferedZipper[M, A]]] = steps match {
      case N #:: nps => z.nextT.value.flatMap {
        case None    => go(z, nps)
        case Some(b) => go(b, nps)
      }
      case P #:: nps => z.prevT.value.flatMap {
        case None    => go(z, nps)
        case Some(b) => go(b, nps)
      }
      case Stream.Empty => m.point(Option(z))
    }

    OptionT(go(bz, path))
  }

  def toWindowBufferOnPath[A](first: A, l: Stream[A], limit: Limit, path: Path): WindowBuffer[A] = {
    def go(ll: Stream[A], path: Path, buff: WindowBuffer[A]): WindowBuffer[A] = (ll, path) match {
      case (aa @ a #:: as, step #:: steps) =>
        (step, buff) match {
        case (N, b: NoRight[A])  => go(as, steps, b.next(a))
        case (N, b: HasRight[A]) => go(aa, steps, b.next)
        case (P, b: NoLeft[A])   => go(as, steps, b.prev(a))
        case (P, b: HasLeft[A])  => go(aa, steps, b.prev)
      }
      case _ => buff
    }

    go(l, path, WindowBuffer(first, limit))
  }

  def toWindowBuffer[A](l: List[A], limit: Limit): Option[WindowBuffer[A]] = {
    def go(ll: List[A], wb: WindowBuffer[A]): WindowBuffer[A] = ll match {
      case Nil     => wb
      case a :: as => wb match {
        case buff: HasRight[A] => go(as, buff.next)
        case buff: NoRight[A]  => go(as, buff.next(a))
      }
    }

    l match {
      case Nil     => None
      case a :: as => Some(go(as, WindowBuffer(a, limit)))
    }
  }
}
