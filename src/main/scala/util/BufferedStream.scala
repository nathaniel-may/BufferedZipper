package util

import scalaz.Zipper
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter


case class BufferedStream[T] private (buffer: Vector[Option[T]], zipper: Option[Zipper[() => T]], maxBuffer: Option[Long]){
  val focus: Option[T] = zipper.flatMap(z => buffer.lift(z.index).flatten)
  val buff = buffer // TODO dev
  private[util] val meter = new MemoryMeter

  def next: BufferedStream[T] = zipper
    .flatMap(z => z.next)
    .map { zNext => buffer.lift(zNext.index).flatten
      .fold(
        new BufferedStream[T](if(zNext.index >= buffer.size) buffer :+ Some(zNext.focus()) else buffer.updated(zNext.index, Some(zNext.focus())), Some(zNext), maxBuffer))(_ =>
        new BufferedStream[T](buffer, Some(zNext), maxBuffer)) }
      .map { nextBs => maxBuffer
        .map { max => if(meter.measureDeep(nextBs.buffer) > max) BufferedStream(BufferedStream.shrinkBufferLeft(nextBs.buffer, max), nextBs.zipper, maxBuffer)
                      else this }
        .getOrElse(nextBs) }
    .getOrElse(this)

  def prev: BufferedStream[T] = zipper
    .flatMap(z => z.previous)
    .map { z => buffer.lift(z.index).flatten
      .fold(
        new BufferedStream[T](buffer.updated(z.index, Some(z.focus())), Some(z), maxBuffer))(_ =>
        new BufferedStream[T](buffer, Some(z), maxBuffer)) }
    .map { nextBs => maxBuffer
      .map { max => if(meter.measureDeep(nextBs.buffer) > max) BufferedStream(BufferedStream.shrinkBufferRight(nextBs.buffer, max), nextBs.zipper, maxBuffer)
                    else this }
      .getOrElse(nextBs) }
    .getOrElse(this)
}

object BufferedStream {
  private[util] val meter = new MemoryMeter

  def apply[T](stream: Stream[T], maxBuffer: Option[Long] = None): BufferedStream[T] = {
    val z = stream.map(() => _).toZipper
    val buff = z.map(zip => Vector[Option[T]](Some(zip.focus()))).getOrElse(Vector[Option[T]]())
    new BufferedStream[T](buff, z, maxBuffer)
  }

  private[util] def shrinkBufferLeft[T](buffer: Vector[Option[T]], max: Long): Vector[Option[T]] =
    if (meter.measureDeep(buffer) <= max) buffer
    else setFirstToNone(buffer, buffer.indices.toList)

  private[util] def shrinkBufferRight[T](buffer: Vector[Option[T]], max: Long): Vector[Option[T]] =
    if (meter.measureDeep(buffer) <= max) buffer
    else setFirstToNone(buffer, buffer.indices.reverse.toList)

  private[util] def setFirstToNone[T](buffer: Vector[Option[T]], indices: List[Int]): Vector[Option[T]] = indices match {
    case i :: _ if buffer(i).isDefined => buffer.updated(i, None)
    case _ :: is                       => setFirstToNone(buffer, is)
    case _                             => buffer // they're all None already
  }


}
