package util

import scalaz.State
import Stream.Empty

object BufferedStream {
  type BS[T] = State[BufferedStream[T], Option[T]]

  def apply[T](stream: Stream[T]): BufferedStream[T] =
    new BufferedStream[T](-1, Vector[T](), stream)

  def next[T]: BS[T] = for {
    bs <- State.get[BufferedStream[T]]
    (pos, buffer, stream) = (bs.pos, bs.buffer, bs.stream)
    _    <- if (pos+1 < buffer.size) State.put(BufferedStream(pos+1, buffer,                stream))
            else if(stream.isEmpty)  State.put(BufferedStream(pos,   buffer,                stream))
            else                     State.put(BufferedStream(pos+1, buffer :+ stream.head, stream.tail))
    next =  if (pos+1 < buffer.size) buffer.lift(pos+1)
            else stream match {
              case Empty   => buffer.lift(pos)
              case p #:: _ => Some(p) }
  } yield next

  def prev[T]: BS[T] = for {
    bs <- State.get[BufferedStream[T]]
    (pos, buffer, stream) = (bs.pos, bs.buffer, bs.stream)
    _    <- if (pos > 0) State.put(BufferedStream(pos-1, buffer, stream))
            else         State.put(BufferedStream(pos,   buffer, stream))
    prev =  if      (pos >  0) buffer.lift(pos-1)
            else if (pos == 0) buffer.headOption
            else               None
  } yield prev
}

case class BufferedStream[T] private (pos: Int, buffer: Vector[T], stream: Stream[T])


//object BufferedStream {
//  def apply[A](stream: Stream[A]): BufferedStream[A] = BufferedStream[A](0, Vector(), stream)
//}
//
//case class BufferedStream[A] private (pos: Int, buffer: Vector[A], stream: Stream[A]) {
//
//  lazy val next: (BufferedStream[A], Option[A]) =
//    if (pos+1 < buffer.size) (pos+1, buffer, stream, buffer.get(pos+1))
//    else stream match {
//      case Stream.Empty => (BufferedStream(pos,   buffer,      stream), buffer.get(pos))
//      case p #:: ps     => (BufferedStream(pos+1, buffer :+ p, ps),     Some(p))
//    }
//
//  lazy val prev: (BufferedStream[A], Option[A]) =
//    if (pos > 0) (BufferedStream(pos-1, buffer, stream), buffer.get(pos-1))
//    else         (BufferedStream(pos,   buffer, stream), buffer.headOption)
//
//}
