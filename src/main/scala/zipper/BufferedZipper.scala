package zipper

// Scala
import scalaz.{Monad, Zipper, OptionT}
import scalaz.syntax.monad.{ToFunctorOps, ToBindOps, ToFunctorOpsUnapply}
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import scala.language.higherKinds


case class BufferedZipper[M[_], A] private (buffer: WindowBuffer[A], zipper: Zipper[M[A]])(implicit m: Monad[M]) {

  val index: Int = zipper.index
  val focus: A   = buffer.focus

  def next: Option[M[BufferedZipper[M, A]]] = zipper.next.map { zNext =>
    buffer match {
      case buff: HasRight[A] => m.point(BufferedZipper(buff.next, zNext))
      case buff: NoRight[A]  => zNext.focus.map(focus => BufferedZipper(buff.next(focus), zNext))
    }
  }

  def nextT: OptionT[M, BufferedZipper[M, A]] = zipper.next match {
    case None        => OptionT(m.point(None))
    case Some(zNext) => buffer match {
      case buff: HasRight[A] => OptionT(m.point(Some(BufferedZipper(buff.next, zNext))))
      case buff: NoRight[A]  => OptionT(zNext.focus.map { focus =>
        Some(BufferedZipper(buff.next(focus), zNext)) } )
    }
  }

  def prev: Option[M[BufferedZipper[M, A]]] = zipper.previous.map { zPrev =>
    buffer match {
      case buff: HasLeft[A] => m.point(BufferedZipper(buff.prev, zPrev))
      case buff: NoLeft[A]  => zPrev.focus.map(focus => BufferedZipper(buff.prev(focus), zPrev))
    }
  }

  def prevT: OptionT[M, BufferedZipper[M, A]] = zipper.previous match {
    case None        => OptionT(m.point(None: Option[BufferedZipper[M, A]]))
    case Some(zPrev) => buffer match {
      case buff: HasLeft[A] => OptionT(m.point(Option(BufferedZipper(buff.prev, zPrev))))
      case buff: NoLeft[A]  => OptionT(zPrev.focus.map { focus =>
        Some(BufferedZipper(buff.prev(focus), zPrev)) } )
    }
  }

  def map[B](f: A => B): BufferedZipper[M, B] =
    new BufferedZipper(buffer.map(f), zipper.map(_.map(f)))

  /**
    * Traverses the entirety of the contents twice in order to reuse what is in the
    * buffer to minimize effects.
    *
    * Elements to the right of the focus are traversed once and reversed to be obtained
    * in the correct order. Elements to the left of the focus are traversed once, then
    * traversed again to append the focus and all elements to the right.
    */
  def toStream: M[Stream[A]] = {
    def goLeft(bz: BufferedZipper[M, A], l: M[Stream[A]]): M[Stream[A]] =
      bz.prev.fold(l) { pmbz => pmbz.flatMap { pbz => goLeft(pbz, l.map(pbz.focus #:: _)) } }

    def goRight(bz: BufferedZipper[M, A], l: M[Stream[A]]): M[Stream[A]] =
      bz.next.fold(l.map(_.reverse)) { nmbz => nmbz.flatMap { nbz => goRight(nbz, l.map(nbz.focus #:: _)) } }

    for {
      l <- goLeft (this, m.point(Stream()))
      r <- goRight(this, m.point(Stream()))
    } yield l #::: focus #:: r
  }
}

object BufferedZipper {
  def apply[M[_], A](stream: Stream[M[A]], limit: Limit)(implicit m: Monad[M]): Option[M[BufferedZipper[M, A]]] =
    stream.toZipper
      .map { zip => zip.focus
        .map { t => new BufferedZipper(WindowBuffer(t, limit), zip) } }

  def applyT[M[_], A](stream: Stream[M[A]], limit: Limit)(implicit m: Monad[M]): OptionT[M, BufferedZipper[M, A]] =
    stream.toZipper match {
      case None      => OptionT(m.point(None))
      case Some(zip) => OptionT(zip.focus.map { t =>
        Some(new BufferedZipper(WindowBuffer(t, limit), zip)) } )
    }
}

object MyTypes {
  sealed trait NP
  object N extends NP { override def toString: String = "N" }
  object P extends NP { override def toString: String = "P" }
}