package util

import org.github.jamm.MemoryMeter

/*
focus:                A
storage: [3, 2, 1, 0]   [0, 1]
*/


trait WindowBuffer[A] {
  import WindowBuffer.{LR, L, R, meter}

  val focus: A
  protected val rightStorage: Vector[A]
  protected val leftStorage: Vector[A]
  protected val size: Long
  protected val maxSize: Long

  private[util] def shrink(storage: Vector[A], size: Long): (Vector[A], Long) =
    if (size <= maxSize) (storage, size)
    else storage.lift(storage.size - 1)
      .fold[(Vector[A], Long)]((Vector(), size)) { a =>
      shrink(storage.tail, size - meter.measureDeep(a)) }

  private[util] def shiftWindow(a: A, shift: LR) = {
    val (storage, constructor) = shift match {
      case L => (rightStorage, LeftEndBuffer.apply(_, _, _, _))
      case R => (leftStorage, RightEndBuffer.apply(_, _, _, _))
    }

    val (newStorage, newSize) = shrink(focus +: storage, size + WindowBuffer.meter.measureDeep(a))
    if (newStorage.isEmpty) DoubleEndBuffer(a, maxSize)
    else constructor(newStorage, a, newSize, maxSize)
  }

  private[util] def moveWithinWindow(move: LR) = {
    val (farStorage, nearStorage, constructor) = move match {
      case L => (rightStorage, leftStorage, LeftEndBuffer.apply(_, _, _, _))
      case R => (leftStorage, rightStorage, RightEndBuffer.apply(_, _, _, _))
    }

    val (newStorage, newSize) = shrink(focus +: farStorage, size + WindowBuffer.meter.measureDeep(focus))
    nearStorage match {
      case newFocus +: a +: as => MidBuffer(a +: as, newStorage, newFocus, newSize, maxSize)
      case newFocus +: _       => constructor(newStorage, newFocus, newSize, maxSize)
      case _ => this // unreachable if all goes well
    }
  }
}

object WindowBuffer {
  val meter = new MemoryMeter

  trait LR
  object L extends LR
  object R extends LR

  def apply[A](a: A, maxSize: Long): DoubleEndBuffer[A] = DoubleEndBuffer(a, maxSize)
}

trait NoLeft[A] extends WindowBuffer[A] {
  def prev(a: A): WindowBuffer[A] = shiftWindow(a, WindowBuffer.L)
}

trait NoRight[A] extends WindowBuffer[A] {
  def next(a: A): WindowBuffer[A] = shiftWindow(a, WindowBuffer.R)
}

trait HasLeft[A] extends WindowBuffer[A] {
  def prev: WindowBuffer[A] = moveWithinWindow(WindowBuffer.L)
}

trait HasRight[A] extends WindowBuffer[A] {
  def next: WindowBuffer[A] = moveWithinWindow(WindowBuffer.R)
}

private[util] final case class MidBuffer[A] private (
  leftStorage:  Vector[A],
  rightStorage: Vector[A],
  focus:        A,
  size:         Long,
  maxSize:      Long) extends WindowBuffer[A] with HasLeft[A] with HasRight[A]

private[util] final case class LeftEndBuffer[A] private (
  rightStorage: Vector[A],
  focus:        A,
  size:         Long,
  maxSize:      Long) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {

  val leftStorage  = Vector()
}

private[util] final case class RightEndBuffer[A] private (
  leftStorage:  Vector[A],
  focus:        A,
  size:         Long,
  maxSize:      Long) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {

  val rightStorage = Vector()
}

private[util] final case class DoubleEndBuffer[A] private (
  focus:   A,
  size:    Long,
  maxSize: Long) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {

  val leftStorage  = Vector()
  val rightStorage = Vector()
}

object DoubleEndBuffer {
  def apply[A](a: A, maxSize: Long): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, 0L, maxSize)
  }
}