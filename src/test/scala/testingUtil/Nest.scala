package testingUtil

import scalaz.Scalaz.unfold

trait Pair[+A, +B]

case class ABPair[+A, +B](a: A, b: B) extends Pair[A, B]
case class BAPair[+A, +B](b: B, a: A) extends Pair[A, B]

case class NestCons[+A, +B](outer: Pair[A, B], inner: Nest[A, B]) extends Nest[A, B]
case object EmptyNest extends Nest[Nothing, Nothing]

object Nest {
  def apply[A, B](pairs: Pair[A, B]*): Nest[A, B] =
    pairs.foldLeft[Nest[A, B]](EmptyNest){ case (nest, p) => NestCons(p, nest) }
}

sealed trait Nest[+A, +B] {
  val depth: Int = this match {
    case EmptyNest         => 0
    case NestCons(_, nest) => 1 + nest.depth
  }

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
    case NestCons(pair, innerNest) => NestCons(pair, prepend(innerNest))
  }

  def append[C >: A, D >: B](nest: Nest[C, D]): Nest[C, D] = nest.prepend(this)

  def pluck(index: Int): Nest[A, B] = this.take(index) prepend this.drop(index + 1)

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
        case ABPair(a, b) => go(nps, Left(a) :: lefts, Right(b) :: rights)
        case BAPair(b, a) => go(nps, Right(b) :: lefts, Left(a) :: rights)
      }
    }

    go(this, List(), List())
  }
}