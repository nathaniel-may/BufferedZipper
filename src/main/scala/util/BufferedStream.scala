package util

import scalaz.State

case class BufferedStream[T] private (pos: Int, buffer: Vector[T], stream: Stream[T], value: Option[T]){
  val current: Option[T] = value
  def next: (BufferedStream[T], Option[T]) = BufferedStream.next[T].run(this)
  def prev: (BufferedStream[T], Option[T]) = BufferedStream.prev[T].run(this)
  def mapBuffer(f: Vector[T] => Vector[T]): BufferedStream[T] = BufferedStream(pos, f(buffer), stream, value)
}

object BufferedStream {
  type BS[T] = State[BufferedStream[T], Option[T]]

  def apply[T](stream: Stream[T]): BufferedStream[T] =
    new BufferedStream[T](-1, Vector[T](), stream, None)

  def next[T]: BS[T] = for {
    bs <- State.get[BufferedStream[T]]
    (pos, buffer, stream) = (bs.pos, bs.buffer, bs.stream)
    _ <- if (pos+1 < buffer.size) State.put(BufferedStream(pos+1, buffer,                stream,      buffer.lift(pos+1)))
         else if(stream.isEmpty)  State.put(BufferedStream(pos,   buffer,                stream,      buffer.lift(pos)))
         else                     State.put(BufferedStream(pos+1, buffer :+ stream.head, stream.tail, stream.headOption))
    next <- State.get[BufferedStream[T]]
  } yield next.value

  def prev[T]: BS[T] = for {
    bs <- State.get[BufferedStream[T]]
    (pos, buffer, stream) = (bs.pos, bs.buffer, bs.stream)
    _ <- if      (pos >  0) State.put(BufferedStream(pos-1, buffer, stream, buffer.lift(pos-1)))
         else if (pos == 0) State.put(BufferedStream(pos,   buffer, stream, buffer.headOption))
         else               State.put(BufferedStream(pos,   buffer, stream, None))
    prev <- State.get[BufferedStream[T]]
  } yield prev.value

}
