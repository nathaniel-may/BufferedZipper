package util

import scalaz.Zipper
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter

// TODO BufferedStream[M[_], T] ?? Pure represented by scalaz.Scalaz.Id
case class BufferedZipper[T] private(buffer: VectorBuffer[T], zipper: Zipper[() => T]){
  val index: Int = zipper.index
  val focus: T   = buffer.lift(index).getOrElse(zipper.focus()) // gets focus from zipper if the buffer size is zero

  def next: Option[BufferedZipper[T]] =
    zipper.next.map {nextZip =>
      new BufferedZipper(buffer.append(nextZip.focus()), nextZip)}

  def prev: Option[BufferedZipper[T]] =
    zipper.next.map {nextZip =>
      new BufferedZipper(buffer.leftFill(nextZip.focus()), nextZip)}

}

object BufferedZipper {
  private[util] val meter = new MemoryMeter

  def apply[T](stream: Stream[T], maxBuffer: Option[Long] = None): Option[BufferedZipper[T]] = {
    stream.map(() => _).toZipper
      .map { zip => (zip, VectorBuffer(maxBuffer).append(zip.focus())) }
      .map { case (zip, buff) => new BufferedZipper(buff, zip) }
  }

  //TODO private[util] dev prints
  def measureBufferContents[T](bs: BufferedZipper[T]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)
}

private[util] case class VectorBuffer[T] private (v: Vector[Option[T]], stats: Option[BufferStats]) {
  import VectorBuffer.{L, R, shrinkToMax, reverseIndices}

  // TODO is this the right approach?
  def lift(i: Int): Option[T] = v.lift(i).flatten

  // TODO change to rightFill which will append if the right-most elem is Some(_)
  def append(elem: T): VectorBuffer[T] =
    shrinkToMax(VectorBuffer(
      v :+ Some(elem),
      stats.map(_.increaseBySizeOf(elem))))(L)

  def leftFill(elem: T): VectorBuffer[T] =
    shrinkToMax(VectorBuffer(
      VectorBuffer.leftFill(v, elem),
      stats.map(_.increaseBySizeOf(elem))))(R)

  private[util] def shrinkLeft: VectorBuffer[T] = {
    val (newV, removedT) = setFirstToNone(v, v.indices.toList)
    VectorBuffer(newV, stats.map(_.decreaseBySizeOf(removedT)))
  }

  private[util] def shrinkRight: VectorBuffer[T] = {
    val (newV, removedT) = setFirstToNone(v, reverseIndices(v).toList)
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

  private[util] def leftFill[T](v: Vector[Option[T]], elem: T): Vector[Option[T]] = {
    def go(indices: List[Int]): Vector[Option[T]] = indices match {
      case i :: _ if v.lift(i).exists(_.isEmpty) => v.updated(i, Some(elem))
      case _ :: is => go(is)
      case Nil => v // TODO Should never get here. Nowhere on the left to fill.
    }

    go(reverseIndices(v).toList)
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