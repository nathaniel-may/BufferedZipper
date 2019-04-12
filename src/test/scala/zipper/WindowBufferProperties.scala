package zipper

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties
import org.scalacheck.Arbitrary.arbInt

// Project
import util.PropertyFunctions._
import util.Generators.{intStreamGen, windowBufferByteLimitGen}

object WindowBufferProperties extends Properties("WindowBuffer") {

  property("List and WindowBuffer.toList are the same with no buffer limit") = forAll {
    (in: List[Int]) => toWindowBuffer(in, Unlimited)
      .fold(in.isEmpty) { _.toList == in }
  }

  property("List map f and WindowBuffer map f are the same with no buffer limit") = forAll {
    (in: List[Int]) => toWindowBuffer(in, Unlimited)
      .fold(in.isEmpty) { _.map(_+1).toList == in.map(_+1) }
  }

  property("never exceeds byte limit") =
    forAll(windowBufferByteLimitGen()(arbInt.arbitrary, intStreamGen)) {
      (buff: WindowBuffer[Int]) => buff.limit match {
        case Bytes(max) => measureBufferContents(buff) <= max
        case _          => false
      }
    }
}
