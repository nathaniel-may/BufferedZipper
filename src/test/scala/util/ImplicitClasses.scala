package util

object ImplicitClasses {


  implicit class ListSyntax[A](l: List[A]) {
    def intersperse(b: List[A]): List[A] = ListSyntax.intersperse(l, b)
  }

  object ListSyntax {
    private def intersperse[A](a: List[A], b: List[A]): List[A] = a match {
      case first :: rest => first :: intersperse(b, rest)
      case _ => b
    }
  }

  implicit class StreamSyntax[A](s: Stream[A]) {
    def intersperse(b: Stream[A]): Stream[A] = StreamSyntax.intersperse(s, b)
  }

  object StreamSyntax {
    private def intersperse[A](a: Stream[A], b: Stream[A]): Stream[A] = a match {
      case first #:: rest => first #:: intersperse(b, rest)
      case _ => b
    }
  }

}
