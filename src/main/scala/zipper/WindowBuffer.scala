package zipper

import org.github.jamm.MemoryMeter

/*
focus:                A
storage: [3, 2, 1, 0]   [0, 1]
*/


trait WindowBuffer[A] {
  import WindowBuffer.meter

  val focus: A
  val maxSize: Option[Long]

  private[zipper] val rights: Vector[A]
  private[zipper] val lefts: Vector[A]
  private[zipper] val contentSize: Long

  private[zipper] lazy val size: Int = lefts.size + 1 + rights.size

  def contains(a: A): Boolean = lefts.contains(a) || rights.contains(a)
  def toList: List[A] = toVector.toList
  def toVector: Vector[A] = lefts.reverse ++: focus +: rights
  def map[B](f: A => B): WindowBuffer[B] = this match {
    case LeftEndBuffer(rs, foc, s, max)  => LeftEndBuffer(rs.map(f), f(foc), s, max)
    case RightEndBuffer(ls, foc, s, max) => RightEndBuffer(ls.map(f), f(foc), s, max)
    case DoubleEndBuffer(foc, s, max)    => DoubleEndBuffer(f(foc), s, max)
    case MidBuffer(ls, rs, foc, s, max)  => MidBuffer(ls.map(f), rs.map(f), f(foc), s, max)
  }


  private[zipper] def shrink(storage: Vector[A], size: Long): (Vector[A], Long) =
    if (maxSize.fold(true) { size <= _ }) (storage, size)
    else storage.lift(storage.size - 1)
      .fold[(Vector[A], Long)]((Vector(), size)) { a =>
      shrink(storage.init, size - meter.measureDeep(a)) }

  private[zipper] def shiftWindowLeft(a: A) = {
    val (newRights, newSize) = shrink(focus +: rights, contentSize + WindowBuffer.meter.measureDeep(a))
    if (newRights.isEmpty) DoubleEndBuffer(a, maxSize)
    else                   LeftEndBuffer(newRights, a, newSize, maxSize)
  }

  private[zipper] def shiftWindowRight(a: A) = {
    val (newLefts, newSize) = shrink(focus +: lefts, contentSize + WindowBuffer.meter.measureDeep(a))
    if (newLefts.isEmpty) DoubleEndBuffer(a, maxSize)
    else                  RightEndBuffer(newLefts, a, newSize, maxSize)
  }

  private[zipper] def moveLeftWithinWindow = {
    val (newRights, newSize) = shrink(focus +: rights, contentSize + WindowBuffer.meter.measureDeep(focus))
    lefts match {
      case newFocus +: IndexedSeq() => LeftEndBuffer(newRights, newFocus, newSize, maxSize)
      case newFocus +: as           => MidBuffer(as, newRights, newFocus, newSize, maxSize)
      case _ => this // unreachable if all goes well
    }
  }

  private[zipper] def moveRightWithinWindow = {
    val (newLefts, newSize) = shrink(focus +: lefts, contentSize + WindowBuffer.meter.measureDeep(focus))
    rights match {
      case newFocus +: IndexedSeq() => RightEndBuffer(newLefts, newFocus, newSize, maxSize)
      case newFocus +: as           => MidBuffer(newLefts, as, newFocus, newSize, maxSize)
      case _ => this // unreachable if all goes well
    }
  }

  override def toString: String = {
    def left = if (lefts.isEmpty) "" else lefts.reverse.mkString("", ", ", " ")
    def right = if (rights.isEmpty) "" else rights.mkString(" ", ", ", "")
    s"WindowBuffer: [$left-> $focus <-$right]"
  }
}

object WindowBuffer {
  val meter = new MemoryMeter

  def apply[A](a: A, maxSize: Option[Long]): DoubleEndBuffer[A] = DoubleEndBuffer(a, maxSize)
}

trait NoLeft[A] extends WindowBuffer[A] {
  def prev(a: A): WindowBuffer[A] = shiftWindowLeft(a)
}

trait NoRight[A] extends WindowBuffer[A] {
  def next(a: A): WindowBuffer[A] = shiftWindowRight(a)
}

trait HasLeft[A] extends WindowBuffer[A] {
  def prev: WindowBuffer[A] = moveLeftWithinWindow
}

trait HasRight[A] extends WindowBuffer[A] {
  def next: WindowBuffer[A] = moveRightWithinWindow
}

private[zipper] final case class MidBuffer[A] private(
  lefts:  Vector[A],
  rights: Vector[A],
  focus:        A,
  contentSize:         Long,
  maxSize:      Option[Long]) extends WindowBuffer[A] with HasLeft[A] with HasRight[A]

private[zipper] final case class LeftEndBuffer[A] private(
  rights: Vector[A],
  focus:        A,
  contentSize:         Long,
  maxSize:      Option[Long]) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {

  val lefts  = Vector()
}

private[zipper] final case class RightEndBuffer[A] private(
  lefts:  Vector[A],
  focus:        A,
  contentSize:         Long,
  maxSize:      Option[Long]) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {

  val rights = Vector()
}

private[zipper] final case class DoubleEndBuffer[A] private(
  focus:   A,
  contentSize:    Long,
  maxSize: Option[Long]) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {

  val lefts  = Vector()
  val rights = Vector()
}

object DoubleEndBuffer {
  def apply[A](a: A, maxSize: Option[Long]): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, 0L, maxSize)
  }
}