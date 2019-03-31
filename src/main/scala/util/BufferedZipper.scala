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
    zNext.focus.map { nextFocus => BufferedZipper[M, A](buffer match {
      case buff: HasRight[A] => buff.next
      case buff: NoRight[A]  => buff.next(nextFocus)
    }, zNext ) } }

  def prev: Option[M[BufferedZipper[M, A]]] = zipper.previous.map { zPrev =>
    zPrev.focus.map { prevFocus => BufferedZipper[M, A](buffer match {
      case buff: HasLeft[A] => buff.prev
      case buff: NoLeft[A]  => buff.prev(prevFocus)
    }, zPrev ) } }

  def toStream: Stream[M[A]] = zipper.toStream

  /**
    * Traverses from the current position all the way to the left, all the way right then reverses the output.
    * This implementation aims to minimize the total effects from M by reusing what is in the buffer rather
    * than minimize traversals.
    */
  def toList: M[List[A]] = zipper.toStream.toList.sequence // TODO write toList to minimize effects

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

object M {
  import BufferStats.meter

  def main(args: Array[String]): Unit = {
    import MyTypes._

//    def measure(bz: BufferedZipper[Id, Int]): Long =
//      bz.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)
    def getInfo(bz: BufferedZipper[Id, Int]): String =
      s"Focus: ${bz.focus}, BufferType: ${getBufferType(bz.buffer)}, BufferContents: ${bz.buffer}"

    def getBufferType[A](buff: WindowBuffer[A]) = buff match {
      case DoubleEndBuffer(_, _, _)   => "DoubleEndBuffer"
      case LeftEndBuffer(_, _, _, _)  => "LeftEndBuffer"
      case MidBuffer(_, _, _, _, _)   => "MidBuffer"
      case RightEndBuffer(_, _, _, _) => "RightEndBuffer"
    }

    def go(bz: BufferedZipper[Id, Int], np: List[NP]): Unit = {
      println(getInfo(bz))
      np match {
        case N :: nps => bz.next.fold(go(bz, nps)) { go(_, nps) }
        case P :: nps => bz.prev.fold(go(bz, nps)) { go(_, nps) }
        case _        => println()
      }
    }

    def unzipAndMapViaPath[M[_] : Monad, A, B](path: Stream[NP], zipper: BufferedZipper[M, A], f: BufferedZipper[M, A] => B): M[List[B]] = {
      val monadSyntax = implicitly[Monad[M]].monadSyntax
      import monadSyntax._

      // if the path walks off the zipper it keeps evaluating till it walks back on
      def go(z: BufferedZipper[M, A], steps: Stream[NP], l: M[List[B]]): M[List[B]] = steps match {
        case N #:: ps => z.next.fold(go(z, ps, l)) { mbz =>
          mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _))) }
        case P #:: ps => z.prev.fold(go(z, ps, l)) { mbz =>
          mbz.flatMap(zShift => go(zShift, ps, l.map(f(zShift) :: _))) }
        case Stream.Empty => l.map(_.reverse)
      }

      go(zipper, path, point(List(f(zipper))))
    }

//    BufferedZipper(Stream(-6, 231, 53457), Some(224L))
//      .map(go(_, List(N, N, P, P, N)))
//      .getOrElse(())

    BufferedZipper(Stream(-6, 231, 53457), Some(224L))
      .map(unzipAndMapViaPath[Id, Int, String](Stream(N, N, P, P, N), _, getInfo))
      .map(_.map(println(_)))
  }
}

object MyTypes {
  sealed trait NP
  object N extends NP { override def toString: String = "N" }
  object P extends NP { override def toString: String = "P" }
}