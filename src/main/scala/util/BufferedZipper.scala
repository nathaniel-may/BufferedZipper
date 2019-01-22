package util

import scalaz.Zipper
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter

// TODO BufferedStream[M[_], T] ?? Pure represented by scalaz.Scalaz.Id
case class BufferedZipper[T] private(buffer: VectorBuffer[T], zipper: Zipper[() => T]){
  val index: Int = zipper.index
  val focus: T   = buffer.lift(index).getOrElse(zipper.focus()) // gets focus from zipper if the buffer size is zero

  def next: Option[BufferedZipper[T]] =
    zipper.next.map { nextZip => new BufferedZipper(
      buffer.lift(nextZip.index)
        .fold(buffer.insertRight(nextZip.focus()).getOrElse(buffer.append(nextZip.focus())))(_ => buffer), // TODO focus called twice. higher kinds or val
      nextZip) }

  def prev: Option[BufferedZipper[T]] =
    zipper.previous.flatMap { prevZip => buffer
      .lift(prevZip.index)
      .fold[Option[VectorBuffer[T]]](buffer.insertLeft(prevZip.focus()))(_ => Some(buffer))
      .map(filledBuffer => new BufferedZipper(filledBuffer, prevZip)) }

}

object BufferedZipper {
  private[util] val meter = new MemoryMeter

  def apply[T](stream: Stream[T], maxBuffer: Option[Long] = None): Option[BufferedZipper[T]] = {
    stream.map(() => _).toZipper
      .map { zip => (zip, VectorBuffer(maxBuffer).append(zip.focus())) }
      .map { case (zip, buff) => new BufferedZipper(buff, zip) }
  }

  private[util] def measureBufferContents[T](bs: BufferedZipper[T]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)
}

private[util] case class VectorBuffer[T] private (v: Vector[Option[T]], stats: Option[BufferStats]) {
  import VectorBuffer.{LR, L, R, shrinkToMax, reverseIndices}

  // TODO is this the right approach?
  def lift(i: Int): Option[T] = v.lift(i).flatten

  // TODO change to rightFill which will append if the right-most elem is Some(_)
  def append(elem: T): VectorBuffer[T] =
    shrinkToMax(VectorBuffer(
      v :+ Some(elem),
      stats.map(_.increaseBySizeOf(elem))))(L)

  // returns None instead of appending //TODO inserted v insert?
  def insertRight(elem: T): Option[VectorBuffer[T]] =
    insert(VectorBuffer.insertRight(v, elem), L, elem)

  // returns None instead of prepending
  def insertLeft(elem: T): Option[VectorBuffer[T]] =
    insert(VectorBuffer.insertLeft(v, elem), R, elem)

  def insert(filledBuffer: Option[Vector[Option[T]]], reduceFrom: LR, elem: T): Option[VectorBuffer[T]] =
    filledBuffer.map { fb => shrinkToMax(
      new VectorBuffer(fb, stats.map(_.increaseBySizeOf(elem))))(reduceFrom) }

  private[util] def shrinkLeft:  VectorBuffer[T] = shrink(v.indices)
  private[util] def shrinkRight: VectorBuffer[T] = shrink(reverseIndices(v))

  private[util] def shrink(indices: Range): VectorBuffer[T] = {
    val (newV, removedT) = setFirstToNone(v, indices.toList)
    VectorBuffer(newV, stats.map(s => removedT.fold(s)(s.decreaseBySizeOf)))
  }

  private[util] def setFirstToNone(buffer: Vector[Option[T]], indices: List[Int]): (Vector[Option[T]], Option[T]) = indices match {
    case i :: _ if buffer(i).isDefined => (buffer.updated(i, None), buffer.lift(i).flatten)
    case _ :: is                       => setFirstToNone(buffer, is)
    case _                             => (buffer, None) // they're all None already
  }

}

// TODO REMOVE
object M {
  def main(args: Array[String]): Unit = {
    BufferedZipper(Stream(0, 0), Some(0L))
  }
}

object VectorBuffer {

  trait LR
  object L extends LR
  object R extends LR

  //TODO does it measure in bytes?
  def apply[T](limitBytes: Option[Long]): VectorBuffer[T] =
    new VectorBuffer(
      Vector(),
      limitBytes.map(l => if (l < 0) 0 else l).map(BufferStats(_, 0L)) )

  def reverseIndices[T](v: Vector[T]): Range = v.size-1 to 0 by -1

  private[util] def shrinkToMax[T](vb: VectorBuffer[T], lr: LR): VectorBuffer[T] =
    vb.stats.map(_ => lr match {
      case _ if vb.stats.forall(_.withinMax) => vb
      case _ if vb.v.forall(_.isEmpty)       => vb
      case L => shrinkToMax(vb.shrinkLeft)(lr)
      case R => shrinkToMax(vb.shrinkRight)(R)
    }).getOrElse(vb)

  // if it has stats, shrink it if it's not already within its max
  private[util] def shrinkToMax[T](newVB: VectorBuffer[T]): LR => VectorBuffer[T] =
    lr => newVB.stats
      .map { stats => if (stats.withinMax) newVB else shrinkToMax(newVB, lr) }
      .getOrElse(newVB)

  private[util] def insertLeft[T](v: Vector[Option[T]], elem: T): Option[Vector[Option[T]]] =
    insertFrom(v, elem, reverseIndices(v).toList)

  private[util] def insertRight[T](v: Vector[Option[T]], elem: T): Option[Vector[Option[T]]] =
    insertFrom(v, elem, v.indices.toList)

  private[util] def insertFrom[T](v: Vector[Option[T]], elem: T, indices: List[Int]): Option[Vector[Option[T]]] = indices match {
    case i :: _ if v.lift(i).exists(_.isEmpty) => Some(v.updated(i, Some(elem)))
    case _ :: is                               => insertFrom(v, elem, is)
    case Nil                                   => None
  }

}

private[util] case class BufferStats(maxBufferSize: Long, estimatedBufferSize: Long) {
  import BufferStats.meter
  val withinMax: Boolean = estimatedBufferSize <= maxBufferSize
  def increaseBySizeOf[T](elem: T) = new BufferStats(maxBufferSize, estimatedBufferSize + meter.measureDeep(elem))
  def decreaseBySizeOf[T](elem: T) = new BufferStats(maxBufferSize, estimatedBufferSize - meter.measureDeep(elem))
}

object BufferStats {
  val meter = new MemoryMeter
  def apply[T](maxBufferSize: Long, prevEstimatedBufferSize: Long, elem: T): BufferStats =
    new BufferStats(maxBufferSize, prevEstimatedBufferSize + meter.measureDeep(elem))
}