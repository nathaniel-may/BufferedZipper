package zipper

import org.github.jamm.MemoryMeter

/*
focus:                A
storage: [3, 2, 1, 0]   [0, 1]
*/


sealed trait WindowBuffer[+A] {
  import WindowBuffer.meter

  val focus: A
  val limit: Limit

  private[zipper] val rights:    Vector[A]
  private[zipper] val lefts:     Vector[A]
  private[zipper] val sizeBytes: Long
  private[zipper] lazy val size: Int = lefts.size + 1 + rights.size // lazy to wait till concrete instance gets created

  def contains[B >: A](b: B): Boolean = lefts.contains(b) || rights.contains(b)
  def toList: List[A] = toVector.toList
  def toVector: Vector[A] = lefts.reverse ++: focus +: rights
  def map[B](f: A => B): WindowBuffer[B] = this match {
    case LeftEndBuffer(rs, foc, s, max)  => LeftEndBuffer(rs.map(f), f(foc), s, max)
    case RightEndBuffer(ls, foc, s, max) => RightEndBuffer(ls.map(f), f(foc), s, max)
    case DoubleEndBuffer(foc, s, max)    => DoubleEndBuffer(f(foc), s, max)
    case MidBuffer(ls, rs, foc, s, max)  => MidBuffer(ls.map(f), rs.map(f), f(foc), s, max)
  }

  private[zipper] def shrink[B >: A](storage: Vector[B], bytes: Long): (Vector[B], Long) = limit match {
    case Unlimited => (storage, bytes)
    case Bytes(max) =>
      if (bytes <= max) (storage, bytes)
      else storage
        .lift(storage.size - 1)
        .fold[(Vector[B], Long)]((Vector(), bytes)) { a =>
          shrink(storage.init, bytes - meter.measureDeep(a)) }
    case Size(_) => (storage, bytes) //TODO respect size limit
  }

  private[zipper] def shiftWindowLeft[B >: A](b: B) = {
    val (newRights, newSize) = shrink(focus +: rights, sizeBytes + WindowBuffer.meter.measureDeep(b))
    if (newRights.isEmpty) DoubleEndBuffer(b, limit)
    else                   LeftEndBuffer(newRights, b, newSize, limit)
  }

  private[zipper] def shiftWindowRight[B >: A](b: B) = {
    val (newLefts, newSize) = shrink(focus +: lefts, sizeBytes + WindowBuffer.meter.measureDeep(b))
    if (newLefts.isEmpty) DoubleEndBuffer(b, limit)
    else                  RightEndBuffer(newLefts, b, newSize, limit)
  }

  private[zipper] def moveLeftWithinWindow = {
    val (newRights, newSize) = shrink(focus +: rights, sizeBytes + WindowBuffer.meter.measureDeep(focus))
    lefts match {
      case newFocus +: IndexedSeq() => LeftEndBuffer(newRights, newFocus, newSize, limit)
      case newFocus +: as           => MidBuffer(as, newRights, newFocus, newSize, limit)
      case _ => this // unreachable if all goes well
    }
  }

  private[zipper] def moveRightWithinWindow = {
    val (newLefts, newSize) = shrink(focus +: lefts, sizeBytes + WindowBuffer.meter.measureDeep(focus))
    rights match {
      case newFocus +: IndexedSeq() => RightEndBuffer(newLefts, newFocus, newSize, limit)
      case newFocus +: as           => MidBuffer(newLefts, as, newFocus, newSize, limit)
      case _ => this // unreachable if all goes well
    }
  }

  override def toString: String = {
    def left = if (lefts.isEmpty) "" else lefts.reverse.mkString("", ", ", " ")
    def right = if (rights.isEmpty) "" else rights.mkString(" ", ", ", "")
    s"WindowBuffer: Limit: $limit, [$left-> $focus <-$right]"
  }
}

object WindowBuffer {
  val meter = new MemoryMeter

  def apply[A](a: A, limits: Limit): DoubleEndBuffer[A] = DoubleEndBuffer(a, limits)
}

sealed trait NoLeft[+A] extends WindowBuffer[A] {
  def prev[B >: A](b: B): WindowBuffer[B] = shiftWindowLeft(b)
}

sealed trait NoRight[+A] extends WindowBuffer[A] {
  def next[B >: A](b: B): WindowBuffer[B] = shiftWindowRight(b)
}

sealed trait HasLeft[+A] extends WindowBuffer[A] {
  def prev: WindowBuffer[A] = moveLeftWithinWindow
}

sealed trait HasRight[+A] extends WindowBuffer[A] {
  def next: WindowBuffer[A] = moveRightWithinWindow
}

private[zipper] final case class MidBuffer[A] private(
  lefts:     Vector[A],
  rights:    Vector[A],
  focus:     A,
  sizeBytes: Long,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with HasRight[A]

private[zipper] final case class LeftEndBuffer[A] private(
  rights:    Vector[A],
  focus:     A,
  sizeBytes: Long,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {

  val lefts  = Vector()
}

private[zipper] final case class RightEndBuffer[A] private(
  lefts:     Vector[A],
  focus:     A,
  sizeBytes: Long,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {

  val rights = Vector()
}

private[zipper] final case class DoubleEndBuffer[A] private(
  focus:     A,
  sizeBytes: Long,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {

  val lefts  = Vector()
  val rights = Vector()
}

object DoubleEndBuffer {
  def apply[A](a: A, limits: Limit): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, 0L, limits)
  }
}