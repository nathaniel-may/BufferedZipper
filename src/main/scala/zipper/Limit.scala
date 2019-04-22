package zipper

import org.github.jamm.MemoryMeter
import scala.util.{Failure, Success, Try}


sealed trait Limit

object Unlimited extends Limit

final case class SizeLimit private(max: Int) extends Limit
object SizeLimit {
  def apply(max: Int): SizeLimit = new SizeLimit(if (max < 0) 0 else max)
}

final case class ByteLimit private(max: Long, current: Long) extends Limit {
  def exceeded:    Boolean = current > max
  def notExceeded: Boolean = !exceeded
  def addSizeOf[A](a: A)      = ByteLimit(max, current + ByteLimit.measureDeep(a))
  def subtractSizeOf[A](a: A) = ByteLimit(max, current - ByteLimit.measureDeep(a))
}
object ByteLimit {
  def apply(max: Long): ByteLimit = {
    measureDeep(max) // throws if JVM -javaagent flag isn't set to Jamm
    new ByteLimit(if (max < 0L) 0L else max, 0L)
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