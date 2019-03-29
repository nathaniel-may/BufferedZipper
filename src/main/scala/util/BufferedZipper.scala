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

  def prev: Option[M[BufferedZipper[M, A]]] = zipper.next.map { zPrev =>
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

//object M {
//  import BufferStats.meter
//
//  trait NP
//  object N extends NP
//  object P extends NP
//
//  def main(args: Array[String]): Unit = {
//    def measure(bz: BufferedZipper[Id, Int]): Long =
//      bz.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)
//
//    def go(bz: BufferedZipper[Id, Int], np: List[NP]): Unit = {
//      println(s"Focus: ${bz.focus}, EstBufferSize: ${bz.buffer.stats.get.estimatedBufferSize}, MeasuredBufferSize: ${measure(bz)}, BufferContents: ${bz.buffer.v}")
//      np match {
//        case N :: nps => bz.next.fold(()) { go(_, nps) }
//        case P :: nps => bz.prev.fold(()) { go(_, nps) }
//        case _ => println(s"Focus: ${bz.focus}, EstBufferSize: ${bz.buffer.stats.get.estimatedBufferSize}, MeasuredBufferSize: ${measure(bz)}, BufferContents: ${bz.buffer.v}")
//      }
//    }
//
//    BufferedZipper(Stream(1468352542, 1444746294, 1, 0), Some(224L))
//      .map(go(_, List(N, P, N, P)))
//      .getOrElse(())
//  }
//}