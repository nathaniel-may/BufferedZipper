package zipper

// scala
import cats.data.NonEmptyVector
import cats.implicits._

// project
import WindowBuffer._


sealed trait WindowBuffer[+A] {
  val focus: A
  val limit: Limit

  def size: Int

  def ls: Vector[A] =  WindowBuffer.leftsRights(this)._1
  def rs: Vector[A] =  WindowBuffer.leftsRights(this)._2
  def contains[B >: A](b: B): Boolean = WindowBuffer.contains[A, B](this)(b)
  def toVector: Vector[A] = WindowBuffer.toVector(this)
  def map[B](f: A => B): WindowBuffer[B] = this match {
    case LeftEndBuffer(rs, foc, lim)  => LeftEndBuffer(rs.map(f), f(foc), lim)
    case RightEndBuffer(ls, foc, lim) => RightEndBuffer(ls.map(f), f(foc), lim)
    case DoubleEndBuffer(foc, lim)    => DoubleEndBuffer(f(foc), lim)
    case MidBuffer(ls, rs, foc, lim)  => MidBuffer(ls.map(f), rs.map(f), f(foc), lim)
  }

  override def toString: String = WindowBuffer.toString(this)
}

object WindowBuffer {
  trait LR
  object L extends LR
  object R extends LR

  def apply[A](a: A, limits: Limit): DoubleEndBuffer[A] = DoubleEndBuffer(a, limits)

  private[zipper] def apply[A, B <: A](lefts: Vector[B], rights: Vector[B], focus: B, limit: Limit): WindowBuffer[A] = (lefts, rights) match {
    case (l +: ls, r +: rs) => MidBuffer(NonEmptyVector(l, ls), NonEmptyVector(r, rs), focus, limit)
    case (_,       r +: rs) => LeftEndBuffer(NonEmptyVector(r, rs), focus, limit)
    case (l +: ls, _)       => RightEndBuffer(NonEmptyVector(l, ls), focus, limit)
    case _                  => DoubleEndBuffer(focus, limit)
  }

  def contains[A, B >: A](w: WindowBuffer[A])(b: B): Boolean = w match {
    case ww: HasLeft[A] with HasRight[A] => ww.lefts.toVector.contains(b) || ww.rights.toVector.contains(b)
    case ww: HasLeft[A]                  => ww.lefts.toVector.contains(b)
    case ww:                 HasRight[A] => ww.rights.toVector.contains(b)
    case _                               => false
  }

  def toVector[A](w: WindowBuffer[A]): Vector[A] = (w match {
    case ww: HasLeft[A] with HasRight[A] => ww.lefts.reverse ++: ww.focus +: ww.rights
    case ww: HasLeft[A]                  => ww.lefts.reverse :+ ww.focus
    case ww:                 HasRight[A] => ww.focus +: ww.rights
    case _                               => NonEmptyVector.of(w.focus)
  }).toVector

  def leftsRights[A](w: WindowBuffer[A]): (Vector[A], Vector[A]) = w match {
    case ww: HasLeft[A] with HasRight[A] => (ww.lefts.toVector, ww.rights.toVector)
    case ww: HasLeft[A]                  => (ww.lefts.toVector, Vector())
    case ww:                 HasRight[A] => (Vector(),          ww.rights.toVector)
    case _                               => (Vector(),          Vector())
  }

  def toString[A](wb: WindowBuffer[A]): String = {
    def left (lefts:  Vector[A]) = if (lefts.isEmpty)  "" else lefts.reverse.mkString("", ", ", " ")
    def right(rights: Vector[A]) = if (rights.isEmpty) "" else rights.mkString(" ", ", ", "")
    def getString(lefts: Vector[A], rights: Vector[A], w: WindowBuffer[A]) =
      s"WindowBuffer: Limit: ${w.limit}, [${left(lefts)}-> ${w.focus} <-${right(rights)}]"

    wb match {
      case w: HasRight[A] with HasLeft[A] => getString(w.lefts.toVector, w.rights.toVector, w)
      case w: HasRight[A]                 => getString(Vector(),         w.rights.toVector, w)
      case w:                  HasLeft[A] => getString(w.lefts.toVector, Vector(),          w)
      case w                              => getString(Vector(),         Vector(),          w)
    }
  }

  private[zipper] def adjustLimit[A](limit: Limit, up: A, down: A): Limit = limit match {
    case lim: ByteLimit => lim.subtractSizeOf(down).addSizeOf(up)
    case lim        => lim
  }

  private[zipper] def adjustLimitUp[A](limit: Limit, up: A): Limit = limit match {
    case lim: ByteLimit => lim.addSizeOf(up)
    case lim        => lim
  }

  private[zipper] def shrink[A](forward: Vector[A], behind: Vector[A], limit: Limit): (Vector[A], Vector[A], Limit) = limit match {
    case Unlimited => (forward, behind, Unlimited)

    case lim: ByteLimit =>
      def truncate(first: Vector[A], second: Vector[A], limit: ByteLimit): (Vector[A], Vector[A], ByteLimit) = first match {
        case _ if limit.notExceeded => (first, second, limit)
        case as :+ a                => truncate(as, second, limit.subtractSizeOf(a))
        case _                      => truncate(second, Vector(), limit)
      }
      truncate(behind, forward, lim) match { case (behind, forward, limit) => (forward, behind, limit) }

    case lim @ SizeLimit(max) =>
      if (forward.size + behind.size <= max) (forward, behind, lim)
      else (forward, behind.take(max - behind.size), lim)
  }

  private[zipper] def shiftWindowLeft[A, B <: A](w: NoLeft[A], newFocus: B): WindowBuffer[A] = {
    val (newLefts, newRights, newLimit) = w match {
      case ww: HasRight[A] => shrink[A](Vector(), (ww.focus +: ww.rights).toVector, adjustLimitUp(ww.limit, ww.focus))
      case _ : NoRight[A]  => shrink[A](Vector(), Vector(w.focus), adjustLimitUp(w.limit, w.focus))
    }

    WindowBuffer(newLefts, newRights, newFocus, newLimit)
  }

  private[zipper] def shiftWindowRight[A, B <: A](w: NoRight[A], newFocus: B): WindowBuffer[A] = {
    val (newRights, newLefts, newLimit) = w match {
      case ww: HasLeft[A] => shrink[A](Vector(), (ww.focus +: ww.lefts).toVector, adjustLimitUp(ww.limit, ww.focus))
      case _ : NoLeft[A]  => shrink[A](Vector(), Vector(w.focus), adjustLimitUp(w.limit, w.focus))
    }

    WindowBuffer(newLefts, newRights, newFocus, newLimit)
  }

  private[zipper] def moveLeftWithinWindow[A](w: HasLeft[A]): WindowBuffer[A] = {
    val (newLefts, newRights, newLimit) = w match {
      case ww: HasRight[A] => shrink(ww.lefts.tail, (ww.focus +: ww.rights).toVector, adjustLimit(ww.limit, ww.focus, w.lefts.head))
      case _ : NoRight[A]  => shrink(w.lefts.tail, Vector(w.focus), adjustLimit(w.limit, w.focus, w.lefts.head))
    }

    WindowBuffer(newLefts, newRights, w.lefts.head, newLimit)
  }

  private[zipper] def moveRightWithinWindow[A](w: HasRight[A]): WindowBuffer[A] = {
    val (newRights, newLefts, newLimit) = w match {
      case ww: HasLeft[A] => shrink(ww.rights.tail, (ww.focus +: ww.lefts).toVector, adjustLimit(ww.limit, ww.focus, w.rights.head))
      case _ : NoLeft[A]  => shrink(w.rights.tail, Vector(w.focus), adjustLimit(w.limit, w.focus, w.rights.head))
    }

    WindowBuffer(newLefts, newRights, w.rights.head, newLimit)
  }
}

sealed trait NoLeft[+A] extends WindowBuffer[A] {
  def prev[B >: A](b: B): WindowBuffer[B] = shiftWindowLeft(this, b)
}

sealed trait NoRight[+A] extends WindowBuffer[A] {
  def next[B >: A](b: B): WindowBuffer[B] = shiftWindowRight(this, b)
}

sealed trait HasLeft[+A] extends WindowBuffer[A] {
  val lefts: NonEmptyVector[A]
  def prev: WindowBuffer[A] = moveLeftWithinWindow(this)
}

sealed trait HasRight[+A] extends WindowBuffer[A] {
  val rights: NonEmptyVector[A]
  def next: WindowBuffer[A] = moveRightWithinWindow(this)
}

private[zipper] final case class MidBuffer[+A] private(
  lefts:     NonEmptyVector[A],
  rights:    NonEmptyVector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with HasRight[A] {
  def size: Int = (lefts.size + rights.size).toInt
}

private[zipper] final case class LeftEndBuffer[+A] private(
  rights:    NonEmptyVector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {
  def size: Int = rights.size.toInt
}

private[zipper] final case class RightEndBuffer[+A] private(
  lefts:     NonEmptyVector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {
  def size: Int = lefts.size.toInt
}

private[zipper] final case class DoubleEndBuffer[+A] private(
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {
  def size: Int = 0
}

object DoubleEndBuffer {
  def apply[A](a: A, limit: Limit): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, limit)
  }
}