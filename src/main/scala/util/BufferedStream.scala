package util

import scalaz.State
import Stream.Empty

case class BufferedStream[T] private (pos: Int, buffer: Vector[T], stream: Stream[T])

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
