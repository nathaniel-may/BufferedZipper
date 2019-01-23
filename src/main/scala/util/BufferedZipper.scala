package util

import scalaz.{Id, Monad, Zipper}
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
      nextZip.focus.map { t => new BufferedZipper(buffer.insertRight(t).getOrElse(buffer.append(t)), nextZip, t) })(
      t => implicitly[Monad[M]].point(new BufferedZipper(buffer, nextZip, t))) }

  // TODO don't keep a copy of the focus in the buffer
  def prev: Option[M[BufferedZipper[M, T]]] =
    zipper.previous
      .map { prevZip => buffer.lift(prevZip.index).fold(
        prevZip.focus.map { t => new BufferedZipper(buffer.insertLeft(t).get, prevZip, t)})(
        t => implicitly[Monad[M]].point(new BufferedZipper(buffer, prevZip, t)) ) }
  
}

object BufferedZipper {

  private[util] val meter = new MemoryMeter // TODO this could live lower

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

  def next[M[_]: Monad, T](z: Option[M[BufferedZipper[M, T]]]): Option[M[BufferedZipper[M, T]]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    z.map(mbz => mbz.flatMap(bz => bz.next.getOrElse(mbz)))
  }

  def prev[M[_]: Monad, T](z: Option[M[BufferedZipper[M, T]]]): Option[M[BufferedZipper[M, T]]] = {
    val monadSyntax = implicitly[Monad[M]].monadSyntax
    import monadSyntax._

    z.map(mbz => mbz.flatMap(bz => bz.prev.getOrElse(mbz)))
  }

  //TODO DELETE THIS
  def getBuff[M[_]: Monad, T](bz : BufferedZipper[M, T]): Vector[Option[T]] =
    bz.buffer.v

  private[util] def measureBufferContents[M[_]: Monad, T](bs: BufferedZipper[M, T]): Long =
    bs.buffer.v.map(_.fold(0L)(meter.measureDeep)).fold(0L)(_ + _)
}

private[util] case class VectorBuffer[T] private (v: Vector[Option[T]], stats: Option[BufferStats]) {
  import VectorBuffer.{LR, L, R, shrinkToMax, reverseIndices}

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

object M{
  import scalaz.effect.IO
  import scalaz.std.list._ //sequence on lists
  import scalaz.syntax.traverse._ //sequence

  def main(args: Array[String]): Unit = {
    var effectCount: Int = 0
    val s = Stream(1, 2, 3)
    val sWithEffect = s.map(i => IO {effectCount += 1; i})
    val bz0 = BufferedZipper[IO, Int](sWithEffect, None).get.unsafePerformIO()
    val effectCount0 = effectCount
    val bz1 = bz0.next.get.unsafePerformIO()
    val effectCount1 = effectCount
    val bz2 = bz1.next.get.unsafePerformIO()
    val effectCount2 = effectCount
    val bz3 = bz2.prev.get.unsafePerformIO()
    val effectCount3 = effectCount
    val bz4 = bz3.prev.get.unsafePerformIO()
    val effectCount4 = effectCount

    println(s"0: ${BufferedZipper.getBuff(bz0)}, effectCount: $effectCount0")
    println(s"1: ${BufferedZipper.getBuff(bz1)}, effectCount: $effectCount1")
    println(s"2: ${BufferedZipper.getBuff(bz2)}, effectCount: $effectCount2")
    println(s"1: ${BufferedZipper.getBuff(bz3)}, effectCount: $effectCount3")
    println(s"2: ${BufferedZipper.getBuff(bz4)}, effectCount: $effectCount4")
  }
}