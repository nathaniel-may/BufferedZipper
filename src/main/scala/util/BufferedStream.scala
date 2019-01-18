package util

import scalaz.Zipper
import scalaz.syntax.std.stream.ToStreamOpsFromStream

case class BufferedStream[T] private (buffer: Vector[Option[T]], zipper: Option[Zipper[() => T]]){
  val focus: Option[T] = zipper.flatMap(z => buffer.lift(z.index).flatten)

  lazy val next: BufferedStream[T] = zipper
    .flatMap(z => z.next)
    .map { z => buffer.lift(z.index).flatten
      .fold(new BufferedStream[T](buffer :+ Some(z.focus()), Some(z)))(_ => new BufferedStream[T](buffer, Some(z))) }
    .getOrElse(this)

  lazy val prev: BufferedStream[T] = zipper
    .flatMap(z => z.previous)
    .map { z => buffer.lift(z.index).flatten
      .fold(new BufferedStream[T](buffer.updated(z.index, Some(z.focus())), Some(z)))(_ => new BufferedStream[T](buffer, Some(z))) }
    .getOrElse(this)
}

object BufferedStream {

  def apply[T](stream: Stream[T]): BufferedStream[T] = {
    val z = stream.map(() => _).toZipper
    new BufferedStream[T](z.map(zip => Vector[Option[T]](Some(zip.focus()))).getOrElse(Vector[Option[T]]()), z)
  }

}
