package zipper

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties

// Project
import util.PropertyFunctions._

object WindowBufferProperties extends Properties("WindowBuffer") {
  property("List and WindowBuffer.toList are the same with no buffer limit") = forAll {
    (in: List[Int]) => toWindowBuffer(in, Unlimited)
      .fold(in.isEmpty) { _.toList == in }
  }

  property("List map f and WindowBuffer map f are the same with no buffer limit") = forAll {
    (in: List[Int]) => toWindowBuffer(in, Unlimited)
      .fold(in.isEmpty) { _.map(_+1).toList == in.map(_+1) }
  }
}
