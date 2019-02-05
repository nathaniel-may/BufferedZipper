package testingUtil

import scalaz.Scalaz.unfold


//TODO make some syntax import to make the pretty stuff work easily

//TODO delete this
object SyntaxTest {

  implicit class NestSyntax[A](a: A) {
    def <<[B](nest: Nest[A, B]): UnfinishedABNest[A, B] = UnfinishedABNest(nest, a)
    def <<[B](nest: Nest[B, A]): UnfinishedBANest[B, A] = UnfinishedBANest(nest, a)
    def <<   (nest: Nest[A, A]): UnfinishedABNest[A, A] = UnfinishedABNest(nest, a)
  }

  val nest: Nest[Int, Int] = Nest[Int, Int]((1,2))
  val n1:   Nest[Int, Int] = 0<<nest>>3
  val n2:   Nest[Int, Int] = 0<<(1<< EmptyNest >>2)>>3
  val n0:   Nest[Boolean, String] = EmptyNest
  val n3:   Nest[Boolean, String] = "hey" << (true << n0 >> "hi") >> false

  nest match {
    case EmptyNest       => 1
    case a << nest0 >> b => a+1 << nest0 >> b+1
  }

}

object Pair {
  def <<[A, B](pair: (A, B)) : Pair[A, B] = ABPair[A, B](pair._1, pair._2)
}

trait Pair[+A, +B] {
  def >> : Nest[A, B] = NestCons(this, EmptyNest)
}

case class ABPair[+A, +B](a: A, b: B) extends Pair[A, B]
case class BAPair[+A, +B](b: B, a: A) extends Pair[A, B]

case class NestCons[+A, +B](outer: Pair[A, B], inner: Nest[A, B]) extends Nest[A, B]
case object EmptyNest extends Nest[Nothing, Nothing]

final case class <<[A, B](leftOuter: A, inner: Nest[A, B]) extends Nest[A, B]
final case class >>[A, B](inner: Nest[A, B], rightOuter: B) extends Nest[A, B]

private sealed case class UnfinishedABNest[A, B](nest: Nest[A, B], a: A) extends Pair[A, B] {
  def >>(b: B): Nest[A, B] = NestCons(ABPair(a, b), nest)
  //def ::[B >: A] (x: B): List[B] =
  def <<[C >: A, D >: B]() = ???
}

private sealed case class UnfinishedBANest[A, B](nest: Nest[A,  B], b: B) extends Pair[A, B] {
  def >>(a: A): Nest[A, B] = NestCons(BAPair(b, a), nest)
  //def ::[B >: A] (x: B): List[B] =
  def <<[C >: A, D >: B](a: A) = ???
}

object Nest {
  def apply[A, B](pairs: Pair[A, B]*): Nest[A, B] =
    pairs.foldLeft[Nest[A, B]](EmptyNest){ case (nest, p) => NestCons(p, nest) }

  def apply[A, B](pair: (A, B)) = Nest(ABPair(pair._1, pair._2))
}

sealed trait Nest[+A, +B] {
  val depth: Int = this match {
    case EmptyNest         => 0
    case NestCons(_, nest) => 1 + nest.depth
  }

  val size: Int = depth

  val aDepth: Int = this match {
    case EmptyNest         => 0
    case NestCons(_, nest) => 1 + nest.aDepth
  }

  val bDepth: Int = this match {
    case EmptyNest         => 0
    case NestCons(_, nest) => 1 + nest.bDepth
  }

  def map[C, D](f: Pair[A, B] => Pair[C, D]): Nest[C, D] = this match {
    case EmptyNest         => EmptyNest
    case NestCons(out, in) => NestCons(f(out), in.map(f))
  }

  def insideOut: Nest[A, B] = reverse

  def reverse: Nest[A, B] = {
    def go(input: Nest[A, B], backwards: Nest[A, B]): Nest[A, B] = input match {
      case EmptyNest           => backwards
      case NestCons(out, nest) => go(nest, NestCons(out, backwards))
    }

    go(this, EmptyNest)
  }

  def drop(n: Int): Nest[A, B] = {
    def go(toDrop: Int, in: Nest[A, B]): Nest[A, B] = in match {
      case EmptyNest                     => EmptyNest
      case NestCons(_, _) if toDrop <= 0 => in
      case NestCons(_, nest)             => go(toDrop - 1, nest)
    }

    go(n, this)
  }

  def take(n: Int): Nest[A, B] = {
    def go(toTake: Int, in: Nest[A, B], acc: Nest[A, B]): Nest[A, B] = in match {
      case EmptyNest                     => acc
      case NestCons(_, _) if toTake <= 0 => acc
      case NestCons(out, nest)           => go(toTake - 1, nest, NestCons(out, acc))
    }

    go(n, this, EmptyNest).reverse
  }

  def prepend[C >: A, D >: B](nest: Nest[C, D]): Nest[C, D] = nest match {
    case EmptyNest                 => this
    case NestCons(pair, innerNest) => NestCons(pair, prepend(innerNest)) // TODO compiling but not type checking???
  }

  def prepend[C >: A, D >: B](pair: Pair[C, D]): Nest[C, D] = prepend(Nest(pair))

  def append[C >: A, D >: B](nest: Nest[C, D]): Nest[C, D] = nest.prepend(this)

  def append[C >: A, D >: B](pair: Pair[C, D]): Nest[C, D] = append(Nest(pair))

  def pluck(index: Int): Nest[A, B] = this.take(index) append this.drop(index + 1)

  def lift(index: Int): Option[Pair[A, B]] = this.drop(index) match {
    case EmptyNest         => None
    case NestCons(pair, _) => Some(pair)
  }

  def toStream: Stream[Either[A, B]] = {
    unfold[(Nest[A, B], Stream[Either[A, B]]), Either[A, B]]((this, Stream())) {
      case (EmptyNest, Stream.Empty)            => None
      case (EmptyNest, ab #:: abs)              => Some((ab, (EmptyNest, abs)))
      case (NestCons(ABPair(a, b), nest), tail) => Some((Left(a), (nest, Right(b) #:: tail)))
      case (NestCons(BAPair(b, a), nest), tail) => Some((Right(b), (nest, Left(a) #:: tail)))
    }
  }

  //TODO use toStream.toList instead?
  def toList: List[Either[A, B]] = {
    def go(nested: Nest[A, B], lefts: List[Either[A, B]], rights: List[Either[A, B]]): List[Either[A, B]] = nested match {
      case EmptyNest           => lefts.reverse ::: rights
      case NestCons(pair, nps) => pair match {
        case ABPair(a, b) => go(nps, Left(a)  :: lefts, Right(b) :: rights)
        case BAPair(b, a) => go(nps, Right(b) :: lefts, Left(a)  :: rights)
      }
    }

    go(this, List(), List())
  }

  //TODO del? def ::[B >: A] (x: B): List[B] =
//  def <<[C >: A, D >: B](nest: Nest[C, D]): UnfinishedABNest[C, D] = UnfinishedABNest(nest, a)
//  def <<[C >: A, D >: B](nest: Nest[D, C]): UnfinishedBANest[D, C] = UnfinishedBANest(nest, a)

}