package zipper

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties
import org.scalacheck.Arbitrary.arbInt

// Project
import util.PropertyFunctions._
import util.Generators._


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
    forAll(windowBufferGen()(byteLimitGen, arbInt.arbitrary, intStreamGen)) {
      (buff: WindowBuffer[Int]) => buff.limit match {
        case Bytes(max, _) => measureBufferContents(buff) <= max
        case _             => false
      }
    }

  property("byte size estimate is accurate") =
    forAll(windowBufferGen()(byteLimitGen, arbInt.arbitrary, intStreamGen)) {
      (buff: WindowBuffer[Int]) => buff.limit match {
        case Bytes(_, est) => measureBufferContents(buff) == est
        case _             => false
      }
    }

  property("never exceeds size limit") =
    forAll(windowBufferGen()(sizeLimitGen, arbInt.arbitrary, intStreamGen)) {
      (buff: WindowBuffer[Int]) => buff.limit match {
        case Size(max) => buff.size <= max
        case _         => false
      }
    }

  // TODO this infrequently fails. check the uniqueness of the generator
//  property("never has duplicate items with any limit") =
//    forAll(windowBufferGen()(limitGen, arbInt.arbitrary, uniqueIntStreamGen)) {
//      (buff: WindowBuffer[Int]) =>
//        buff.toList.groupBy(identity).valuesIterator.forall(_.size == 1)
//    }
}
