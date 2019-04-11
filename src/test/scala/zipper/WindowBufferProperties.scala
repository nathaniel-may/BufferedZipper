package zipper

// Scalacheck
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties

object WindowBufferProperties extends Properties("WindowBuffer") {
  property("List and WindowBuffer.toList are the same with no buffer limit") = forAll {
    (in: List[Int]) => {
      def go[A](l: List[A], wb: WindowBuffer[A]): WindowBuffer[A] = l match {
        case Nil     => wb
        case a :: as => wb match {
          case buff: HasRight[A] => go(as, buff.next)
          case buff: NoRight[A]  => go(as, buff.next(a))
        }
      }

      in match {
        case Nil     => true
        case i :: is => go(is, WindowBuffer(i, None)).toList == in
      }
    }
  }
}
