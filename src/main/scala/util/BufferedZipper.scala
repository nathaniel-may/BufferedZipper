package util

import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter

case class BufferedZipper[M[_]: Monad, T] private(buffer: VectorBuffer[T], zipper: Zipper[M[T]], focus: T){
  private val monadSyntax = implicitly[Monad[M]].monadSyntax
  import monadSyntax._

  val index: Int = zipper.index

  // TODO don't keep a copy of the focus in the buffer
  def next: Option[M[BufferedZipper[M, T]]] =
    zipper.next
    .map { nextZip => buffer.lift(nextZip.index).fold(
      nextZip.focus.map { t => new BufferedZipper(buffer.insertedRight(t).getOrElse(buffer.append(t)), nextZip, t) })(
      t => point(new BufferedZipper(buffer, nextZip, t))) }

  // TODO don't keep a copy of the focus in the buffer
  def prev: Option[M[BufferedZipper[M, T]]] =
    zipper.previous
      .map { prevZip => buffer.lift(prevZip.index).fold(
        prevZip.focus.map { t => new BufferedZipper(buffer.insertedLeft(t).get, prevZip, t)})(
        t => point(new BufferedZipper(buffer, prevZip, t)) ) }

}

object BufferedZipper {

  def apply[M[_]: Monad, T](stream: Stream[M[T]], maxBuffer: Option[Long]): Option[M[BufferedZipper[M, T]]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    stream.toZipper
      .map { zip => zip.focus
        .map { t => new BufferedZipper(VectorBuffer(maxBuffer).append(t), zip, t) } }
  }

  def apply[T](stream: Stream[T], maxBuffer: Option[Long]): Option[BufferedZipper[Id, T]] =
    stream.toZipper
      .map { zip => val t = zip.focus
                    new BufferedZipper[Id, T](VectorBuffer(maxBuffer).append(t), implicitly[Monad[Id]].point(zip), t) }
}

private[util] case class VectorBuffer[T] private (v: Vector[Option[T]], stats: Option[BufferStats]) {
  import VectorBuffer.{LR, L, R, shrinkToMax, reverseIndices}

  def lift(i: Int): Option[T] = v.lift(i).flatten

  def append(elem: T): VectorBuffer[T] =
    shrinkToMax(VectorBuffer(
      v :+ Some(elem),
      stats.map(_.increaseBySizeOf(elem))))(L)

  // returns None instead of appending
  def insertedRight(elem: T): Option[VectorBuffer[T]] =
    inserted(VectorBuffer.insertRight(v, elem), L, elem)

  // returns None instead of prepending
  def insertedLeft(elem: T): Option[VectorBuffer[T]] =
    inserted(VectorBuffer.insertLeft(v, elem), R, elem)

  def inserted(filledBuffer: Option[Vector[Option[T]]], reduceFrom: LR, elem: T): Option[VectorBuffer[T]] =
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
  def increaseBySizeOf[T](elem: T) = BufferStats(maxBufferSize, estimatedBufferSize + meter.measureDeep(elem))
  def decreaseBySizeOf[T](elem: T) = BufferStats(maxBufferSize, estimatedBufferSize - meter.measureDeep(elem))
}

object BufferStats {
  val meter = new MemoryMeter
}