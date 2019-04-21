package zipper

import org.github.jamm.MemoryMeter
import scala.util.{Failure, Success, Try}


sealed trait Limit

object Unlimited extends Limit

final case class Size private (max: Int) extends Limit
object Size {
  def apply(max: Int): Size = new Size(if (max < 0) 0 else max)
}

// TODO write property tests for these
final case class Bytes private (max: Long, current: Long) extends Limit {
  def exceeded:    Boolean = current > max
  def notExceeded: Boolean = !exceeded
  def addSizeOf[A](a: A)      = Bytes(max, current + Bytes.measureDeep(a))
  def subtractSizeOf[A](a: A) = Bytes(max, current - Bytes.measureDeep(a))
}
object Bytes {
  def apply(max: Long): Bytes = {
    measureDeep(max) // throws if JVM -javaagent flag isn't set to Jamm
    new Bytes(if (max < 0L) 0L else max, 0L)
  }

  // meter tooling included here so that it is not included unless needed
  private lazy val meter = new MemoryMeter
  private val noJamm =
    new Exception("Set JVM flag -javaagent to Jamm jar file to limit by Byte size. No flags required for size limit.")

  def measureDeep[A](a: A): Long =
    (Try(meter.measureDeep(a)) match {
      case m @ Success(_) => m
      case     Failure(_: java.lang.NullPointerException)  => Success(0L)
      case     Failure(_: java.lang.IllegalStateException) => Failure(noJamm)
      case m @ Failure(_) => m
    }).get // Can only throw runtime exception because this relies on a runtime flag. Throwing early.
}