package zipper


sealed trait Limit

object Unlimited extends Limit

final case class Size private (max: Int) extends Limit
object Size {
  def apply(max: Int): Size = new Size(if (max < 0) 0 else max)
}

final case class Bytes private (max: Long) extends Limit
object Bytes {
  def apply(max: Long): Bytes = new Bytes(if (max < 0L) 0L else max)
}