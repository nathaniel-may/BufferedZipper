package zipper

import org.github.jamm.MemoryMeter

/*
focus:                A
storage: [3, 2, 1, 0]   [0, 1]
*/


trait WindowBuffer[+A] {
  import WindowBuffer.{meter, Limits}

  val focus: A
  val limits: Limits

  private[zipper] val rights: Vector[A]
  private[zipper] val lefts: Vector[A]
  private[zipper] val contentSize: Long

  private[zipper] lazy val size: Int = lefts.size + 1 + rights.size

  def contains[B >: A](b: B): Boolean = lefts.contains(b) || rights.contains(b)
  def toList: List[A] = toVector.toList
  def toVector: Vector[A] = lefts.reverse ++: focus +: rights
  def map[B](f: A => B): WindowBuffer[B] = this match {
    case LeftEndBuffer(rs, foc, s, max)  => LeftEndBuffer(rs.map(f), f(foc), s, max)
    case RightEndBuffer(ls, foc, s, max) => RightEndBuffer(ls.map(f), f(foc), s, max)
    case DoubleEndBuffer(foc, s, max)    => DoubleEndBuffer(f(foc), s, max)
    case MidBuffer(ls, rs, foc, s, max)  => MidBuffer(ls.map(f), rs.map(f), f(foc), s, max)
  }


  private[zipper] def shrink[B >: A](storage: Vector[B], size: Long): (Vector[B], Long) =
    if (limits.maxBytes.fold(true) { size <= _ }) (storage, size)
    else storage.lift(storage.size - 1)
      .fold[(Vector[B], Long)]((Vector(), size)) { a =>
      shrink(storage.init, size - meter.measureDeep(a)) }

  private[zipper] def shiftWindowLeft[B >: A](b: B) = {
    val (newRights, newSize) = shrink(focus +: rights, contentSize + WindowBuffer.meter.measureDeep(b))
    if (newRights.isEmpty) DoubleEndBuffer(b, limits)
    else                   LeftEndBuffer(newRights, b, newSize, limits)
  }

  private[zipper] def shiftWindowRight[B >: A](b: B) = {
    val (newLefts, newSize) = shrink(focus +: lefts, contentSize + WindowBuffer.meter.measureDeep(b))
    if (newLefts.isEmpty) DoubleEndBuffer(b, limits)
    else                  RightEndBuffer(newLefts, b, newSize, limits)
  }

  private[zipper] def moveLeftWithinWindow = {
    val (newRights, newSize) = shrink(focus +: rights, contentSize + WindowBuffer.meter.measureDeep(focus))
    lefts match {
      case newFocus +: IndexedSeq() => LeftEndBuffer(newRights, newFocus, newSize, limits)
      case newFocus +: as           => MidBuffer(as, newRights, newFocus, newSize, limits)
      case _ => this // unreachable if all goes well
    }
  }

  private[zipper] def moveRightWithinWindow = {
    val (newLefts, newSize) = shrink(focus +: lefts, contentSize + WindowBuffer.meter.measureDeep(focus))
    rights match {
      case newFocus +: IndexedSeq() => RightEndBuffer(newLefts, newFocus, newSize, limits)
      case newFocus +: as           => MidBuffer(newLefts, as, newFocus, newSize, limits)
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

  def apply[A](a: A, limits: Limits): DoubleEndBuffer[A] = DoubleEndBuffer(a, limits)

  final case class Limits private (size: Option[Int], maxBytes: Option[Long])
  object Limits {
    val none: Limits = Limits(None, None)

    def apply(size: Option[Int], maxBytes: Option[Long]): Limits =
      new Limits(size.map(nonNeg(_)), maxBytes.map(nonNeg(_)))

    private def nonNeg[A : Numeric](n: A): A = {
      val syntax = implicitly[Numeric[A]]
      import syntax._
      if (lt(n, zero)) zero else n
    }
  }
}

trait NoLeft[+A] extends WindowBuffer[A] {
  def prev[B >: A](b: B): WindowBuffer[B] = shiftWindowLeft(b)
}

trait NoRight[+A] extends WindowBuffer[A] {
  def next[B >: A](b: B): WindowBuffer[B] = shiftWindowRight(b)
}

trait HasLeft[+A] extends WindowBuffer[A] {
  def prev: WindowBuffer[A] = moveLeftWithinWindow
}

trait HasRight[+A] extends WindowBuffer[A] {
  def next: WindowBuffer[A] = moveRightWithinWindow
}

private[zipper] final case class MidBuffer[A] private(
  lefts:       Vector[A],
  rights:      Vector[A],
  focus:       A,
  contentSize: Long,
  limits:      WindowBuffer.Limits) extends WindowBuffer[A] with HasLeft[A] with HasRight[A]

private[zipper] final case class LeftEndBuffer[A] private(
  rights:      Vector[A],
  focus:       A,
  contentSize: Long,
  limits:      WindowBuffer.Limits) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {

  val lefts  = Vector()
}

private[zipper] final case class RightEndBuffer[A] private(
  lefts:       Vector[A],
  focus:       A,
  contentSize: Long,
  limits:      WindowBuffer.Limits) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {

  val rights = Vector()
}

private[zipper] final case class DoubleEndBuffer[A] private(
  focus:       A,
  contentSize: Long,
  limits:      WindowBuffer.Limits) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {

  val lefts  = Vector()
  val rights = Vector()
}

object DoubleEndBuffer {
  import WindowBuffer.Limits

  def apply[A](a: A, limits: Limits): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, 0L, limits)
  }
}