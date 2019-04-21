package zipper

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties
import org.scalacheck.Arbitrary.arbInt
import org.scalacheck.Gen

// Scala
import scala.util.Try
import scalaz.Scalaz.Id

// Project
import util.PropertyFunctions._
import util.Generators._


// Not covered by CI until I fork to another JVM
object NoJavaAgentProperties extends Properties("With no javaagent set") {
  val noEffect = WithEffect[Id]()
  import noEffect.bZipGen

  property("a window buffer throws with a byte limit on creation") = forAll {
    i: Int => Try(WindowBuffer(i, Bytes(16))).isFailure
  }

  property("a window buffer never exceeds size limit") =
    forAll(windowBufferGen()(sizeLimitGen, arbInt.arbitrary, intStreamGen)) {
      (buff: WindowBuffer[Int]) => buff.limit match {
        case Size(max) => buff.size <= max
        case _         => false
      }
    }

  property("a window buffer doesn't crash with no limit") =
    forAll(windowBufferGen()(noLimitGen, arbInt.arbitrary, intStreamGen)) {
      (buff: WindowBuffer[Int]) => buff.limit match {
        case Unlimited => true
        case _         => false
      }
    }

  property("a BufferedZipper throws with a byte limit on creation") = forAll {
    s: Stream[Int] => Try(BufferedZipper[Id, Int](s, Bytes(16))).isFailure
  }

  property("a BufferedZipper size limit is never exceeded") =
    forAll(bZipGen[Int](sizeLimitAtLeast(16)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](bz, path, bzz => bzz.buffer.limit match {
          case Size(max) => bzz.buffer.size <= max
          case _         => false
        })
    }

  property("a BufferedZipper doesn't crash with no limit") =
    forAll(bZipGen[Int](Gen.const(Unlimited)), pathGen) {
      (bz: BufferedZipper[Id, Int], path: Path) =>
        assertOnPath[Id, Int](bz, path, bzz => bzz.buffer.limit match {
          case Unlimited => true
          case _         => false
        })
    }
}

