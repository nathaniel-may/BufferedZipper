package util

import scalaz.Zipper
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter

// TODO BufferedStream[M[_], T] ?? Pure represented by scalaz.Scalaz.Id
case class BufferedStream[T] private (buffer: Vector[Option[T]], zipper: Option[Zipper[() => T]], maxBuffer: Option[Long]){
  import BufferedStream.{L, R, measureAndShrink}
  val focus: Option[T] = zipper.flatMap(z => focusAt(z.index))
  val buff = buffer //TODO remove

  private[util] def focusAt(i: Int): Option[T] = buffer.lift(i).flatten

  def next: BufferedStream[T] = zipper
    .flatMap(_.next)
    .map { zNext => new BufferedStream[T](
      buffer.lift(zNext.index) match {
        case Some(Some(_)) => buffer
        case Some(None)    => buffer.updated(zNext.index, Some(zNext.focus()))
        case None          => buffer :+ Some(zNext.focus())
      },
      Some(zNext),
      maxBuffer) }
    .map { nextBs => measureAndShrink(nextBs, L, maxBuffer) }
    .getOrElse(this)

  def prev: BufferedStream[T] = zipper
    .flatMap(z => z.previous)
    .map { zPrev => new BufferedStream(
      focusAt(zPrev.index)
        .fold(buffer.updated(zPrev.index, Some(zPrev.focus())))(_ => buffer),
      Some(zPrev),
      maxBuffer) }
    .map { nextBs => measureAndShrink(nextBs, R, maxBuffer) }
    .getOrElse(this)
}

object BufferedStream {
  private[util] val meter = new MemoryMeter

  trait LR
  object L extends LR
  object R extends LR

  def apply[T](stream: Stream[T], maxBuffer: Option[Long] = None): BufferedStream[T] = {
    val z = stream.map(() => _).toZipper
    val buff = z.map(zip => Vector[Option[T]](Some(zip.focus()))).getOrElse(Vector[Option[T]]())
    new BufferedStream[T](buff, z, maxBuffer)
  }

  private[util] def rawBuffer[T](bs: BufferedStream[T]): Vector[Option[T]] = bs.buffer

  private[util] def measureAndShrink[T](nextBs: BufferedStream[T], lr: LR, maxBuffer: Option[Long]): BufferedStream[T] =
    maxBuffer.fold(nextBs) { max =>
      if (meter.measureDeep(nextBs.buffer) > max)
        BufferedStream(BufferedStream.shrinkBuffer(nextBs.buffer, lr, max), nextBs.zipper, maxBuffer)
      else nextBs }

  //TODO measure each item and increment size counter. make sure the decrement when evicting from buffer too.
  private[util] def shrinkBuffer[T](buffer: Vector[Option[T]], lr: LR, max: Long): Vector[Option[T]] =
    if (meter.measureDeep(buffer) <= max) buffer
    else lr match {
      case L => shrinkBuffer(setFirstToNone(buffer, buffer.indices.toList),         L, max)
      case R => shrinkBuffer(setFirstToNone(buffer, buffer.indices.reverse.toList), R, max)
    }

  private[util] def setFirstToNone[T](buffer: Vector[Option[T]], indices: List[Int]): Vector[Option[T]] = indices match {
    case i :: _ if buffer(i).isDefined => buffer.updated(i, None)
    case _ :: is                       => setFirstToNone(buffer, is)
    case _                             => buffer // they're all None already
  }

}