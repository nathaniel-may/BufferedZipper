package util

import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import scalaz._, Scalaz._ //TODO minimize for sequence
import org.github.jamm.MemoryMeter


case class BufferedZipper[M[_]: Monad, A] private(buffer: WindowBuffer[A], zipper: Zipper[M[A]]) {
  private val monadSyntax = implicitly[Monad[M]].monadSyntax
  import monadSyntax._

  val index: Int = zipper.index
  val focus: A   = buffer.focus

  def next: Option[M[BufferedZipper[M, A]]] = zipper.next.map { zNext =>
    buffer match {
      case buff: HasRight[A] => point(BufferedZipper(buff.next, zNext))
      case buff: NoRight[A]  => zNext.focus.map(focus => BufferedZipper(buff.next(focus), zNext))
    }
  }

  def prev: Option[M[BufferedZipper[M, A]]] = zipper.previous.map { zPrev =>
    buffer match {
      case buff: HasLeft[A] => point(BufferedZipper(buff.prev, zPrev))
      case buff: NoLeft[A]  => zPrev.focus.map(focus => BufferedZipper(buff.prev(focus), zPrev))
    }
  }
  
  def map[B](f: A => B): BufferedZipper[M, B] =
    new BufferedZipper(buffer.map(f), zipper.map(_.map(f)))

  /**
    * Traverses from the current position all the way to the left, and all the way right.
    * Prepends left to focus + right which means the left side is traversed twice.
    *
    * This implementation aims to minimize the total effects from M by reusing what is
    * in the buffer rather than minimize traversals.
    */
  def toStream: M[Stream[A]] = {
    def goLeft(bz: BufferedZipper[M, A], l: M[Stream[A]]): M[Stream[A]] =
      bz.prev.fold(l) { pmbz => pmbz.flatMap { pbz => goLeft(pbz, l.map(pbz.focus #:: _)) } }

    def goRight(bz: BufferedZipper[M, A], l: M[Stream[A]]): M[Stream[A]] =
      bz.next.fold(l.map(_.reverse)) { nmbz => nmbz.flatMap { nbz => goRight(nbz, l.map(nbz.focus #:: _)) } }

    for {
      l <- goLeft(this, point(Stream()))
      r <- goRight(this, point(Stream()))
    } yield l #::: focus #:: r
  }

}

object BufferedZipper {

  def apply[M[_]: Monad, A](stream: Stream[M[A]], maxBuffer: Option[Long]): Option[M[BufferedZipper[M, A]]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    stream.toZipper
      .map { zip => zip.focus
        .map { t => new BufferedZipper(WindowBuffer(t, maxBuffer.map(zeroNegatives)), zip) } }
  }

  def apply[A](stream: Stream[A], maxBuffer: Option[Long]): Option[BufferedZipper[Id, A]] =
    stream.toZipper
      .map { zip => new BufferedZipper[Id, A](WindowBuffer(zip.focus, maxBuffer.map(zeroNegatives)), implicitly[Monad[Id]].point(zip)) }

  private def zeroNegatives(n: Long): Long =
    if (n < 0) 0 else n

}

object BufferStats {
  val meter = new MemoryMeter
}

object MyTypes {
  sealed trait NP
  object N extends NP { override def toString: String = "N" }
  object P extends NP { override def toString: String = "P" }
}