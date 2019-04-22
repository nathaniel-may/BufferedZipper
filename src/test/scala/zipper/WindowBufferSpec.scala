package zipper

// scalatest
import org.scalatest.Matchers._
import org.scalatest._

// scala
import cats.data.Writer
import cats.effect.IO
import cats.implicits._


class WindowBufferSpec extends FlatSpec {

  "WindowBuffer" should "have the right toString" in {
    val w0 = WindowBuffer(0, Unlimited)
    val w1 = w0.next(1)
    val w2 = w1 match {
      case ww: NoRight[Int]  => ww.next(2)
      case ww: HasRight[Int] => ww.next
    }
    val w3 = w2 match {
      case ww: NoLeft[Int]  => ww.prev(1)
      case ww: HasLeft[Int] => ww.prev
    }
    
    w3.toString.startsWith("WindowBuffer: Limit: zipper.") shouldBe true
    w3.toString.endsWith("[0 -> 1 <- 2]") shouldBe true

    w2.toString.startsWith("WindowBuffer: Limit: zipper.") shouldBe true
    w2.toString.endsWith("[0, 1 -> 2 <-]") shouldBe true

    w1.toString.startsWith("WindowBuffer: Limit: zipper.") shouldBe true
    w1.toString.endsWith("[0 -> 1 <-]") shouldBe true

    w0.toString.startsWith("WindowBuffer: Limit: zipper.") shouldBe true
    w0.toString.endsWith("[-> 0 <-]") shouldBe true
  }
}
