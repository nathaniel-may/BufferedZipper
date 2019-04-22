# BufferedZipper
[![CircleCI](https://circleci.com/gh/nathaniel-may/BufferedZipper.svg?style=svg)](https://circleci.com/gh/nathaniel-may/BufferedZipper)
[![codecov](https://codecov.io/gh/nathaniel-may/BufferedZipper/branch/master/graph/badge.svg)](https://codecov.io/gh/nathaniel-may/BufferedZipper)
[![](https://jitpack.io/v/nathaniel-may/BufferedZipper.svg)](https://jitpack.io/#nathaniel-may/BufferedZipper)


`BufferedZipper` is a data type that manages effectful buffering compatible with `cats-core` and `cats-effect`.

Buffer size can be limited by either the number of elements or by the in-memory size of the elements. In order for the zipper to remain operational with buffer sizes of `0` the focus is _not_ included in measurements of buffers.  

## Getting Started
In build.sbt add the jitpack resolver and library dependency:

```
resolvers += "jitpack" at "https://jitpack.io"
```
```
libraryDependencies += "com.github.nathaniel-may" % "BufferedZipper" % "0.9.0"
```

Before running the `BufferedZipper` with a `ByteLimit` restriction, the JVM being used to run the code must have the `javaagent` flag set to the `jamm` jar file. See the [jamm package](https://github.com/jbellis/jamm) for more information.
```
-javaagent:<path to>/jamm.jar
```

To use your local `.ivy2` cache
```
-javaagent:$HOME/.ivy2/cache/com.github.jbellis/jamm/jars/jamm-0.3.3.jar
```

## Usage
This example uses the alternative methods `nextT`, `prevT`, and the function `applyT` that return the `cats.data.OptionT` monad transformer for composability. Also featured are the more standard `next`, `prev`, and `apply`.
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
val buffT = BufferedZipper.applyT(ioStream, SizeLimit(0))
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

### Limiting the Buffer
`BufferedZipper.apply` and `BufferedZipper.applyT` both take an input of type `Limit`. Here is how to specify each type of limit:
```scala
import zipper.{BufferedZipper, Unlimited, SizeLimit, ByteLimit}

type Id[A] = A //no effect
val input = Stream(1,2,3,4,5)

// Nothing is ever evicted from the buffer
val noLimit = BufferedZipper[Id, Int](input, Unlimited)

// The buffer will keep up to 10 elements and the focus in memory 
val tenItems = BufferedZipper[Id, Int](input, SizeLimit(10))

// `ByteLimit` requires the JVM runtime flag `javaagent` to be set to the `jamm` jar. Throws an exception if not enabled.
// the cumulative size of the elements in the buffer, excluding the focus, will never exceed 10k
val tenKb = BufferedZipper[Id, Int](input, BytesLimit(10240))
```


## Testing
Testing is primarily done with `scalacheck`. `sbt test` forks tests to two separate JVMs: one with `jamm` set as the `javaagent` to run the majority of tests, and one without `javaagent` set to verify that the runtime exception is thrown on creation of a `ByteLimit`, and that operations which do not use a `ByteLimit` can still run. The examples in this readme file are verified with `scalatest` unit tests. 

## Dependencies
- `cats-core`:  The user interface requires `cats-core` to specify generic effects
- `scalaz-core`: Until cats adds a [zipper instance](https://github.com/typelevel/cats/issues/1156), scalaz must be internally used for Zipper representations.
- `jamm`:       Measuring tool for estimating the size of in memory objects. Requires the `javaagent` JVM flag to be set at runtime.

## Future Work:
- `SLF4J` log message when running without Jamm java agent and a ByteLimit is created
- scalajs compatibility
- cats / scalaz interop
- more human readable input options to ByteLimit (kb, Kb, mb, Mb, etc.)
- stretch: Buffer ahead within a threaded context