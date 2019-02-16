package util

import org.scalacheck.Shrink
import scalaz.Scalaz.Id
import testingUtil.Arbitrarily.StreamAndPath
import testingUtil.Path
import BufferTypes._

object Shrinkers {
  implicit val shrinkBufferSize: Shrink[BufferSize] = Shrink[BufferSize] {
    buffSize => Stream.tabulate((buffSize.cap / 16L).toInt)(i => buffSize.cap - i*16L)
      .flatMap(shrunkCap => FlexibleBuffer(shrunkCap).fold[Stream[FlexibleBuffer]](Stream())(Stream(_)))
  }

  implicit val shrinkPath: Shrink[Path] = Shrink { path =>
    path.noLoops #:: path.ls.toStream.indices.toStream.map(path.keepOneLoop)
  }

  //shrinks the path, then the stream
  implicit val shrinkStreamAndPath: Shrink[StreamAndPath[Id, Int]] = Shrink[StreamAndPath[Id, Int]] { sp =>
    println(s"shrinking: $sp")
    val loopStream = sp.path.ls.toStream

    val shrunkPaths: Stream[StreamAndPath[Id, Int]] = loopStream.indices.map(sp.path.keepOneLoop).toStream.append(Stream(sp.path.noLoops))
      .map(p => StreamAndPath[Id, Int](sp.stream, p, sp.limits))

    val shrunkStreams: Stream[StreamAndPath[Id, Int]] =
      sp.stream.indices.reverse.toStream.map {
        i => (sp.stream.take(i), sp.path.ls.take(i).toStream.zipWithIndex.map {
          case (loop, index) => loop.fold(identity, identity).shrinkNReachTo(i-index-1) }) } //TODO check this -1
        .map { case (s, loops) => StreamAndPath[Id, Int](s, Path(loops), sp.limits) }

    val shrunkStreamsLessLoops: Stream[StreamAndPath[Id, Int]] =
      shrunkStreams.flatMap { shrunkSp =>
        shrunkSp.path.ls.toStream.indices.toStream.map(shrunkSp.path.keepOneLoop)
          .append(Stream(shrunkSp.path.noLoops))
          .map(p => StreamAndPath[Id, Int](sp.stream, p, sp.limits)) }

    shrunkPaths #::: shrunkStreams #::: shrunkStreamsLessLoops
      .filter(shrunkSp => sp.limits.fold(true)(limit => shrunkSp.stream.size >= limit.min && shrunkSp.stream.size <= limit.max))
      .filter(shrunkSp => shrunkSp.stream != sp.stream || shrunkSp.path != sp.path) //TODO this is a hack
  }
}
