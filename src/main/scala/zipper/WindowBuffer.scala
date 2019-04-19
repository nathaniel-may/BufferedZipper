package zipper

/*
focus:                A
storage: [3, 2, 1, 0]   [0, 1]
*/


sealed trait WindowBuffer[+A] {
  val focus: A
  val limit: Limit

  private[zipper] val rights:    Vector[A]
  private[zipper] val lefts:     Vector[A]

  // lazy to wait till concrete instance gets created
  // doesn't include focus so that size limit of 0 can still process workloads. sizeBytes doesn't contain it either.
  lazy val size: Int = lefts.size + rights.size

  def contains[B >: A](b: B): Boolean = lefts.contains(b) || rights.contains(b)
  def toList: List[A] = toVector.toList
  def toVector: Vector[A] = lefts.reverse ++: focus +: rights
  def map[B](f: A => B): WindowBuffer[B] = this match {
    case LeftEndBuffer(rs, foc, lim)  => LeftEndBuffer(rs.map(f), f(foc), lim)
    case RightEndBuffer(ls, foc, lim) => RightEndBuffer(ls.map(f), f(foc), lim)
    case DoubleEndBuffer(foc, lim)    => DoubleEndBuffer(f(foc), lim)
    case MidBuffer(ls, rs, foc, lim)  => MidBuffer(ls.map(f), rs.map(f), f(foc), lim)
  }

  private[zipper] def shrink[B >: A](storage: Vector[B], oldFocus: B, newFocusFromBuffer: Option[B]): (Vector[B], Limit) = limit match {
    case Unlimited => (oldFocus +: storage, Unlimited)

    case Bytes(max, current) =>
      def truncate(v: Vector[B], bytes: Long): (Vector[B], Bytes) =
        if (bytes <= max) (v, Bytes(max, bytes))
        else v match {
          case bs :+ b => truncate(bs, bytes - Bytes.measureDeep(b))
          case _       => (v, Bytes(max, bytes))
        }

      val oldFocusBytes = Bytes.measureDeep(oldFocus)
      val newFocusBytes = newFocusFromBuffer.fold(0L)(Bytes.measureDeep)
      val newBytesEst = current + oldFocusBytes - newFocusBytes
      if (newBytesEst <= max) (oldFocus +: storage, Bytes(max, newBytesEst))
      else truncate(oldFocus +: storage, current + oldFocusBytes)

    case Size(max) =>
      if (size + 1 <= max) (oldFocus +: storage, Size(max))
      else ((oldFocus +: storage).take(max), Size(max))
  }

  private[zipper] def shiftWindowLeft[B >: A](b: B) = {
    val (newRights, newLimit) = shrink(rights, focus, None)
    if (newRights.isEmpty) DoubleEndBuffer(b, newLimit)
    else                   LeftEndBuffer(newRights, b, newLimit)
  }

  private[zipper] def shiftWindowRight[B >: A](b: B) = {
    val (newLefts, newLimit) = shrink(lefts, focus, None)
    if (newLefts.isEmpty) DoubleEndBuffer(b, newLimit)
    else                  RightEndBuffer(newLefts, b, newLimit)
  }

  private[zipper] def moveLeftWithinWindow = {
    val (newRights, newLimit) = shrink(rights, focus, lefts.headOption)
    lefts match {
      case newFocus +: IndexedSeq() => LeftEndBuffer(newRights, newFocus, newLimit)
      case newFocus +: as           => MidBuffer(as, newRights, newFocus, newLimit)
      case _ => this // unreachable if all goes well
    }
  }

  private[zipper] def moveRightWithinWindow = {
    val (newLefts, newLimit) = shrink(lefts, focus, rights.headOption)
    rights match {
      case newFocus +: IndexedSeq() => RightEndBuffer(newLefts, newFocus, newLimit)
      case newFocus +: as           => MidBuffer(newLefts, as, newFocus, newLimit)
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
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with HasRight[A]

private[zipper] final case class LeftEndBuffer[A] private(
  rights:    Vector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {

  val lefts  = Vector()
}

private[zipper] final case class RightEndBuffer[A] private(
  lefts:     Vector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {

  val rights = Vector()
}

private[zipper] final case class DoubleEndBuffer[A] private(
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {

  val lefts  = Vector()
  val rights = Vector()
}

object DoubleEndBuffer {
  def apply[A](a: A, limit: Limit): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, limit)
  }
}