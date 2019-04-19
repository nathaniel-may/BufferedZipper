package zipper

import cats.data.NonEmptyVector
import cats.implicits._

import WindowBuffer._

/*
focus:                A
storage: [3, 2, 1, 0]   [0, 1]
*/

sealed trait WindowBuffer[+A] {
  val focus: A
  val limit: Limit

  def size: Int

  def contains[B >: A](b: B): Boolean = WindowBuffer.contains[A, B](this)(b)
  def toList: List[A] = toVector.toList
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

  private[zipper] def apply[A](lefts: Vector[A], rights: Vector[A], focus: A, limit: Limit): WindowBuffer[A] = (lefts, rights) match {
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
    case _                               => NonEmptyVector(w.focus, Vector())
  }).toVector

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

  def shrink[A](forward: Vector[A], behind: Vector[A], limit: Limit): (Vector[A], Vector[A], Limit) = limit match {
    case lim @ Unlimited => (forward, behind, lim)

    case Bytes(max, current) =>
      def truncate(first: Vector[A], second: Vector[A], bytes: Long, max: Long): (Vector[A], Vector[A], Bytes) = first match {
        case _       if bytes <= max => (first, second, Bytes(max, bytes))
        case as :+ a                 => truncate(as,     second,   bytes - Bytes.measureDeep(a), max)
        case _                       => truncate(second, Vector(), bytes, max)
      }
      truncate(behind, forward, current, max)

    case lim @ Size(max) =>
      if (forward.size + behind.size <= max) (forward, behind, lim)
      else (forward, behind.take(max - behind.size), lim)
  }

  private[zipper] def shiftWindowLeft[A, B >: A](w: NoLeft[A], b: B): WindowBuffer[B] = {
    val (newLefts, newRights, newLimit) = w match {
      case ww: HasRight[A] => shrink(Vector(), (ww.focus +: ww.rights).toVector, ww.limit)
      case _ : NoRight[A]  => (Vector(), Vector(), w.limit)
    }

    WindowBuffer[B](newLefts, newRights, b, newLimit)
  }

  private[zipper] def shiftWindowRight[A, B >: A](w: NoRight[A], b: B): WindowBuffer[B] = {
    val (newRights, newLefts, newLimit) = w match {
      case ww: HasLeft[A] => shrink(Vector(), (ww.focus +: ww.lefts).toVector, ww.limit)
      case _ : NoLeft[A]  => (Vector(), Vector(), w.limit)
    }

    WindowBuffer[B](newLefts, newRights, b, newLimit)
  }

  private[zipper] def moveLeftWithinWindow[A](w: HasLeft[A]): WindowBuffer[A] = {
    val (newLefts, newRights, newLimit) = w match {
      case ww: HasRight[A] => shrink(ww.lefts.tail, (ww.focus +: ww.rights).toVector, ww.limit)
      case ww: NoRight[A]  => (ww.lefts.tail, Vector(), ww.limit)
    }

    WindowBuffer[A](newLefts, newRights, w.lefts.head, newLimit)
  }

  private[zipper] def moveRightWithinWindow[A](w: HasRight[A]): WindowBuffer[A] = {
    val (newRights, newLefts, newLimit) = w match {
      case ww: HasLeft[A] => shrink(ww.rights.tail, (ww.focus +: ww.lefts).toVector, ww.limit)
      case ww: NoLeft[A]  => (ww.rights.tail, Vector(), ww.limit)
    }

    WindowBuffer[A](newLefts, newRights, w.rights.head, newLimit)
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

private[zipper] final case class MidBuffer[A] private(
  lefts:     NonEmptyVector[A],
  rights:    NonEmptyVector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with HasRight[A] {
  def size: Int = (lefts.size + rights.size).toInt
}

private[zipper] final case class LeftEndBuffer[A] private(
  rights:    NonEmptyVector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with HasRight[A] {
  def size: Int = rights.size.toInt
}

private[zipper] final case class RightEndBuffer[A] private(
  lefts:     NonEmptyVector[A],
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with HasLeft[A] with NoRight[A] {
  def size: Int = lefts.size.toInt
}

private[zipper] final case class DoubleEndBuffer[A] private(
  focus:     A,
  limit:     Limit) extends WindowBuffer[A] with NoLeft[A] with NoRight[A] {
  def size: Int = 0
}

object DoubleEndBuffer {
  def apply[A](a: A, limit: Limit): DoubleEndBuffer[A] = {
    new DoubleEndBuffer[A](a, limit)
  }
}