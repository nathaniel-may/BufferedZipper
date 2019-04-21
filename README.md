# BufferedZipper

BufferedZipper is a data type that manages effectful buffering with simple configuration.

Buffer size can be limited by either the number of elements or by the in-memory size of the elements. In order for the zipper to remain operational with buffer sizes of 0 the focus is _not_ included in measurements of buffers.  

## Examples
```scala
import scalaz.effect.IO
import zipper.BufferedZipper

val wordStream = "the effects only happen once"
  .split(" ")
  .toStream

val ioStream: Stream[IO[String]] = wordStream
  .map { s => Vector(s).tell.map(_ => s) }

val buffT = BufferedZipper.applyT(writerStream, Unlimited)

(for {
  b <- buffT
  b <- b.nextT
  b <- b.nextT
  b <- b.nextT
  b <- b.nextT
  b <- b.prevT
  b <- b.prevT
  b <- b.prevT
  b <- b.prevT
  b <- b.prevT
} yield b).run.run._1 shouldBe wordStream.toVector
```
output:
```
the
effects
only
happen
once
```

Make a smaller buffer to see the effects occur more than once
```scala 
val buffT = BufferedZipper.applyT(writerStream, SizeLimit(0))
```
output:
```
the
effects
only
happen
once
happen
only
effects
the
```

### Dependencies
BufferedZipper lists both `cats-core` and `scalaz-core` as dependencies because scalaz does not have a `NonEmptyVector` (the [NonEmptyList](https://github.com/scalaz/scalaz/blob/fabab8f699d56279d6f2cc28d02cc2b768e314d7/core/src/main/scala/scalaz/NonEmptyList.scala) does not suffice) and cats does not have a [zipper](https://github.com/typelevel/cats/issues/1156).

### Future Work:
- SLF4J log message when running without Jamm java agent and a ByteLimit is created
- scalajs compatibility
- stretch: Buffer ahead within a threaded context