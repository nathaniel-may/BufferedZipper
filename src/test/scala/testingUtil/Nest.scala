package testingUtil

import scalaz.Scalaz.unfold
import Nest._

case class NestCons[A, B](outer: Pair[A, B], inner: Nest[A, B]) extends Nest[A, B] {

  lazy val depth:  Int = 1 + inner.depth
  lazy val aDepth: Int = 1 + inner.aDepth
  lazy val bDepth: Int = 1 + inner.bDepth

  def map[C, D](f: Pair[A, B] => Pair[C, D]): Nest[C, D] =
    NestCons(f(outer), inner.map(f))

  def insideOut: Nest[A, B] = reverse

  def reverse: Nest[A, B] = {
    def go(input: Nest[A, B], backwards: Nest[A, B]): Nest[A, B] = input match {
      case Nest.Empty => backwards
      case NestCons(out, nest) => go(nest, NestCons(out, backwards))
    }

    go(this, Nest.Empty[A, B])
  }

  def drop(n: Int): Nest[A, B] = {
    def go(toDrop: Int, in: Nest[A, B]): Nest[A, B] = in match {
      case Nest.Empty => Nest.Empty[A, B]
      case NestCons(_, _) if toDrop <= 0 => in
      case NestCons(_, nest) => go(toDrop - 1, nest)
    }

    go(n, this)
  }

  def take(n: Int): Nest[A, B] = {
    def go(toTake: Int, in: Nest[A, B], acc: Nest[A, B]): Nest[A, B] = in match {
      case Nest.Empty => acc
      case NestCons(_, _) if toTake <= 0 => acc
      case NestCons(out, nest) => go(toTake - 1, nest, NestCons(out, acc))
    }

    go(n, this, Nest.Empty[A, B]).reverse
  }

  def prepend(nest: Nest[A, B]): Nest[A, B] = nest match {
    case Nest.Empty                 => this
    case NestCons(pair, innerNest)  => NestCons(pair, prepend(innerNest))
  }

  def append(nest: Nest[A, B]): Nest[A, B] = nest.prepend(this)

  def pluck(index: Int): Nest[A, B] = this.take(index) prepend this.drop(index+1)

  def toStream: Stream[Either[A, B]] = {
    unfold[(Nest[A, B], Stream[Either[A, B]]), Either[A, B]]((this, Stream())){
      case (Nest.Empty, Stream.Empty)           => None
      case (Nest.Empty, ab #:: abs)             => Some((ab, (Nest.Empty[A, B], abs)))
      case (NestCons(ABPair(a, b), nest), tail) => Some((Left(a),  (nest, Right(b) #:: tail)))
      case (NestCons(BAPair(b, a), nest), tail) => Some((Right(b), (nest, Left(a)  #:: tail)))
    }
  }

  //TODO use toStream.toList instead?
  def toList: List[Either[A, B]] = {
    def go(nested: Nest[A, B], lefts: List[Either[A, B]], rights: List[Either[A, B]]): List[Either[A, B]] = nested match {
      case Empty                  => lefts.reverse ::: rights
      case NestCons(pair, nps) => pair match {
        case ABPair(a, b) => go(nps, Left(a)  :: lefts, Right(b) :: rights)
        case BAPair(b, a) => go(nps, Right(b) :: lefts, Left(a)  :: rights)
      }
    }

    go(this, List(), List())
  }
}

trait Nest[A, B] {
  val depth:  Int
  val aDepth: Int
  val bDepth: Int

  def map[C, D](f: Pair[A, B] => Pair[C, D]): Nest[C, D]
  def insideOut: Nest[A, B]
  def reverse: Nest[A, B]
  def drop(n: Int): Nest[A, B]
  def take(n: Int): Nest[A, B]
  def prepend(nest: Nest[A, B]): Nest[A, B]
  def append(nest: Nest[A, B]): Nest[A, B]
  def pluck(index: Int): Nest[A, B]
  def toList: List[Either[A, B]]
  def toStream: Stream[Either[A, B]]
}

object Nest {
  trait Pair[A, B]

  case class ABPair[A, B](a: A, b: B) extends Pair[A, B]
  case class BAPair[A, B](b: B, a: A) extends Pair[A, B]

  //TODO better way to deal with the type params?
  object Empty extends Nest[_, _] {
    val depth  = 0
    val aDepth = 0
    val bDepth = 0

    def map[C, D](f: Pair[_, _] => Pair[C, D]): Nest[C, D] = Empty[C, D]
    def insideOut: Nest[_, _] = this
    def reverse: Nest[_, _] = this
    def drop(n: Int): Nest[_, _] = this
    def take(n: Int): Nest[_, _] = this
    def prepend(nest: Nest[_, _]): Nest[_, _] = nest
    def append(nest: Nest[_, _]): Nest[_, _] = nest
    def pluck(index: Int): Nest[_, _] = this
    def toList: List  [Either[_, _]] = List()
    def toStream: Stream[Either[_, _]] = Stream()
  }
}