# BufferedZipper

BufferedZipper is a data type that manages effectful buffering with `cats-effect`.

Buffer size can be limited by either the number of elements or by the in-memory size of the elements. In order for the zipper to remain operational with buffer sizes of `0` the focus is _not_ included in measurements of buffers.  

## Examples
```scala
import cats.effect.IO
import zipper.{BufferedZipper, Unlimited}

val wordStream = "the effects only happen once"
      .split(" ")
      .toStream

    val ioStream: Stream[IO[String]] = wordStream
      .map { s => IO { println(s); s } }

    val buffT = BufferedZipper.applyT(ioStream, Unlimited)

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
    } yield b).value.unsafeRunSync()
```
output:
```
> the
> effects
> only
> happen
> once
```

Make a smaller buffer to see the effects occur more than once
```scala 
val buffT = BufferedZipper.applyT(writerStream, SizeLimit(0))
```
output:
```
> the
> effects
> only
> happen
> once
> happen
> only
> effects
> the
```

## Testing
Testing is primarily done with `scalacheck`. `sbt test` forks tests to two separate JVMs: one with `jamm` set as the `javaagent` to run the majority of tests, and one without `javaagent` set to verify that the runtime exception is thrown on creation of a `ByteLimit`, and that operations which do not use a `ByteLimit` can still run. The examples in this readme file are verified with `scalatest` unit tests. 

## Dependencies
- `cats-core`:  The user interface requires `cats-core` to specify generic effects
- `sclaz-core`: Until cats adds a [zipper instance](https://github.com/typelevel/cats/issues/1156), internally scalaz must be used for Zipper representations.
- `jamm`:       Measuring tool to estimate the size of in memory objects. Requires the `javaagent` JVM flag to be set at runtime.

## Future Work:
- `SLF4J` log message when running without Jamm java agent and a ByteLimit is created
- scalajs compatibility
- cats / scalaz interop
- stretch: Buffer ahead within a threaded context