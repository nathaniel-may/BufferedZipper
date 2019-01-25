package util

import scalaz.{Monad, Zipper}
import scalaz.Scalaz.Id
import scalaz.syntax.std.stream.ToStreamOpsFromStream
import org.github.jamm.MemoryMeter

case class BufferedZipper[M[_]: Monad, A] private(buffer: VectorBuffer[A], zipper: Zipper[M[A]], focus: A){
  private val monadSyntax = implicitly[Monad[M]].monadSyntax
  import monadSyntax._

  val index: Int = zipper.index

  def next: Option[M[BufferedZipper[M, A]]] = zipper.next.map { zNext =>
    shiftTo(zNext, buffer => if(buffer.size <= index) buffer.append(focus) else buffer.evict(zNext.index).updated(index, focus)) }

  def prev: Option[M[BufferedZipper[M, A]]] = zipper.previous.map { zPrev =>
    shiftTo(zPrev, buffer => buffer.evict(zPrev.index).updated(index, focus)) }

  def toStream: Stream[M[A]] = zipper.toStream

  /**
    * Traverses from the current position all the way to the left, all the right then reverses the output.
    * This implementation aims to minimize the total effects from M by reusing what is in the buffer rather
    * than minimize traversals.
    */
  def toList: M[List[A]] = {
    def go(bz: BufferedZipper[M, A], l: M[List[A]]): M[List[A]] = bz.next match {
      case Some(mbz) => mbz.flatMap(go(_, l.map(bz.focus :: _)))
      case None      => l.map(bz.focus :: _)
    }

    def goToHead(bz: BufferedZipper[M, A]): M[BufferedZipper[M,A]] = bz.prev match {
      case Some(mbz) => mbz.flatMap(goToHead)
      case None      => point(bz)
    }

    goToHead(this).flatMap(head => go(head, point(List())).map(_.reverse))
  }

  private[util] def shiftTo(z: Zipper[M[A]], shift: VectorBuffer[A] => VectorBuffer[A]): M[BufferedZipper[M, A]] =
    buffer.lift(z.index).fold(
      z.focus.map { t => new BufferedZipper(shift(buffer), z, t)})(
      t => point(new BufferedZipper(buffer.evict(z.index).updated(index, focus), z, t)) )

}

object BufferedZipper {

  def apply[M[_]: Monad, T](stream: Stream[M[T]], maxBuffer: Option[Long]): Option[M[BufferedZipper[M, T]]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    stream.toZipper
      .map { zip => zip.focus
        .map { t => new BufferedZipper(VectorBuffer(maxBuffer), zip, t) } }
  }

  def apply[T](stream: Stream[T], maxBuffer: Option[Long]): Option[BufferedZipper[Id, T]] =
    stream.toZipper
      .map { zip => val t = zip.focus
                    new BufferedZipper[Id, T](VectorBuffer(maxBuffer), implicitly[Monad[Id]].point(zip), t) }

}

private[util] case class VectorBuffer[T] private (v: Vector[Option[T]], stats: Option[BufferStats]) {
  import VectorBuffer.{LR, L, R, shrinkToMax, reverseIndices}

  val size: Int = v.size

  def lift(i: Int): Option[T] = v.lift(i).flatten

  // does not append or prepend
  def updated(i: Int, t: T): VectorBuffer[T] =
    v.lift(i).fold(this){ _ => shrinkToMax(VectorBuffer(v.updated(i, Some(t)), stats.map(_.decreaseBySizeOf(t))))(L) } // TODO shouldn't ALWAYS shrink from the left

  def append(elem: T): VectorBuffer[T] =
    shrinkToMax(VectorBuffer(
      v :+ Some(elem),
      stats.map(_.increaseBySizeOf(elem))))(L)

  // returns None instead of appending
  def insertedRight(elem: T): Option[VectorBuffer[T]] =
    inserted(VectorBuffer.insertedRight(v, elem), L, elem)

  // returns None instead of prepending
  def insertedLeft(elem: T): Option[VectorBuffer[T]] =
    inserted(VectorBuffer.insertedLeft(v, elem), R, elem)

  def inserted(filledBuffer: Option[Vector[Option[T]]], reduceFrom: LR, elem: T): Option[VectorBuffer[T]] =
    filledBuffer.map { fb => shrinkToMax(
      new VectorBuffer(fb, stats.map(_.increaseBySizeOf(elem))))(reduceFrom) }

  private[util] def shrinkLeft:  VectorBuffer[T] = shrink(v.indices)
  private[util] def shrinkRight: VectorBuffer[T] = shrink(reverseIndices(v))

  private[util] def shrink(indices: Range): VectorBuffer[T] = {
    val (newV, removedT) = setFirstToNone(v, indices.toList)
    VectorBuffer(newV, stats.map(s => removedT.fold(s)(s.decreaseBySizeOf)))
  }

  def evict(i: Int): VectorBuffer[T] =
    v.lift(i).fold(this)(elem => new VectorBuffer(v.updated(i, None), stats.map(_.decreaseBySizeOf(elem))))

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

  private[util] def insertedLeft[T](v: Vector[Option[T]], elem: T): Option[Vector[Option[T]]] =
    insertedFrom(v, elem, reverseIndices(v).toList)

  private[util] def insertedRight[T](v: Vector[Option[T]], elem: T): Option[Vector[Option[T]]] =
    insertedFrom(v, elem, v.indices.toList)

  private[util] def insertedFrom[T](v: Vector[Option[T]], elem: T, indices: List[Int]): Option[Vector[Option[T]]] = indices match {
    case i :: _ if v.lift(i).exists(_.isEmpty) => Some(v.updated(i, Some(elem)))
    case _ :: is                               => insertedFrom(v, elem, is)
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

// TODO what is the meter really measuring in? remember this is measuring all the weird java things like locks and stuff.
object M {
  def main(args: Array[String]): Unit = {
    val meter = new MemoryMeter
    val byte:  Byte  = 0
    val short: Short = 5
    val int:   Int   = 5
    val long:  Long  = 5L

    println(s"byte:  ${meter.measureDeep(byte)}")
    println(s"short: ${meter.measureDeep(short)}")
    println(s"int:   ${meter.measureDeep(int)}")
    println(s"long:  ${meter.measureDeep(long)}")
  }
}