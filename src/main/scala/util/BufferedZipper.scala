package util

import scalaz.Zipper
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter

// TODO BufferedStream[M[_], T] ?? Pure represented by scalaz.Scalaz.Id
case class BufferedZipper[T] private(buffer: VectorBuffer[T], zipper: Zipper[() => T], focus: T){
  val index: Int = zipper.index
  val buff = buffer //TODO remove

//  private[util] def focusAt(i: Int): Option[T] = buffer.lift(i).flatten

  def next: Option[BufferedZipper[T]] = ???

  def prev: Option[BufferedZipper[T]] = ???

//  def next: Option[BufferedZipper[T]] = zipper
//    .flatMap(_.next)
//    .map { zNext => new BufferedZipper[T](
//      buffer.lift(zNext.index) match {
//        case Some(Some(_)) => buffer
//        case Some(None)    => buffer.updated(zNext.index, Some(zNext.focus()))
//        case None          => buffer :+ Some(zNext.focus())
//      },
//      Some(zNext),
//      Some(zNext.focus()), //TODO refactor so not option
//      bufferStats.map(_.updated(zNext.focus())) )}
//    .map { nextBs => measureAndShrink(nextBs, L, bufferStats) }
//
//  def prev: Option[BufferedZipper[T]] = zipper
//    .flatMap(z => z.previous)
//    .map { zPrev => new BufferedZipper(
//      focusAt(zPrev.index)
//        .fold(buffer.updated(zPrev.index, Some(zPrev.focus())))(_ => buffer),
//      Some(zPrev),
//      maxBuffer,
//      this.estimatedBufferSize) }
//    .map { nextBs => measureAndShrink(nextBs, R, maxBuffer) }
}

object BufferedZipper {
  private[util] val meter = new MemoryMeter

  def apply[T](stream: Stream[T], maxBuffer: Option[Long] = None): Option[BufferedZipper[T]] = {
    stream
      .map(() => _)
      .toZipper
      .map(zip => (zip, VectorBuffer(Vector(zip.focus()))))
      .map { case (zip, buff) => new BufferedZipper(buff, zip, buff.lift(0).get, 0) }
  }

//  private[util] def rawBuffer[T](bs: BufferedZipper[T]): Vector[Option[T]] = bs.buffer
//
//  private[util] def measureBuffer[T](bs: BufferedZipper[T]): Long = meter.measureDeep(bs.buffer)
//
//  private[util] def measureAndShrink[T](nextBs: BufferedZipper[T], lr: LR, bufferStats: Option[BufferStats]): BufferedZipper[T] =
//    bufferStats.fold(nextBs) { stats =>
//      if (meter.measureDeep(nextBs.buffer) > stats.maxBufferSize)
//        BufferedZipper(BufferedZipper.shrinkBuffer(nextBs.buffer, lr, stats), nextBs.zipper, bufferStats)
//      else nextBs }
//
//  //TODO measure each item and increment size counter. make sure the decrement when evicting from buffer too.
//  private[util] def shrinkBuffer[T](buffer: Vector[Option[T]], lr: LR, stats: BufferStats): Vector[Option[T]] =
//    if (meter.measureDeep(buffer) <= max) buffer
//    else lr match {
//      case L => shrinkBuffer(setFirstToNone(buffer, buffer.indices.toList),         L, max)
//      case R => shrinkBuffer(setFirstToNone(buffer, buffer.indices.reverse.toList), R, max)
//    }

}

private[util] case class VectorBuffer[T] private (v: Vector[Option[T]], stats: Option[BufferStats]) {
  import VectorBuffer.{L, R, shrinkToMax, reverseIndices}

  // TODO is this the right approach?
  def lift(i: Int): Option[T] = v.lift(i).flatten

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
    VectorBuffer(newV, stats.map(_.decreaseBySizeOf(removedT)))
  }

  private[util] def setFirstToNone(buffer: Vector[Option[T]], indices: List[Int]): (Vector[Option[T]], Option[T]) = indices match {
    case i :: _ if buffer(i).isDefined => (buffer.updated(i, None), buffer.lift(i).flatten)
    case _ :: is                       => setFirstToNone(buffer, is)
    case _                             => (buffer, None) // they're all None already
  }

}

object VectorBuffer {

  trait LR
  object L extends LR
  object R extends LR

  //TODO does it measure in bytes?
  def apply[T](v: Vector[T], limitBytes: Option[Long] = None) =
    new VectorBuffer(v.map(Some(_)), limitBytes.map( max => BufferStats(max, v.map(BufferStats.meter.measureDeep).fold(0L)(_ + _))))

  def reverseIndices[T](v: Vector[T]): Range = v.size-1 to 0 by -1

  private[util] def shrinkToMax[T](vb: VectorBuffer[T], lr: LR): VectorBuffer[T] =
    vb.stats.map(_ => lr match {
      case _ if vb.stats.map(_.withinMax) => vb
      case _ if vb.v.forall(_.isEmpty)    => vb
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
  val exceedsMaxBy: Option[Long] = if (withinMax) None else Some(estimatedBufferSize - maxBufferSize) //TODO delete?
  def increaseBySizeOf[T](elem: T) = new BufferStats(maxBufferSize, estimatedBufferSize + meter.measureDeep(elem))
  def decreaseBySizeOf[T](elem: T) = new BufferStats(maxBufferSize, estimatedBufferSize - meter.measureDeep(elem))
}

object BufferStats {
  val meter = new MemoryMeter
  def apply[T](maxBufferSize: Long, prevEstimatedBufferSize: Long, elem: T): BufferStats =
    new BufferStats(maxBufferSize, prevEstimatedBufferSize + meter.measureDeep(elem))
}