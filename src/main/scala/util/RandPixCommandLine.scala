package util

// Java
import java.io.File
import java.nio.file.{Path, Paths}

// Scala
import cats.effect.{ExitCode, IO, IOApp}

// Project
import util.Shuffle.shuffle
import util.CommandLine.{printlnSafe, readUntil}

object RandPixCommandLine extends IOApp {

  def getFiles(dir: File): IO[Stream[Path]] =
    if (dir.exists && dir.isDirectory) IO {
      dir.listFiles()
        .toStream
        .filter(!_.isDirectory)
        .map(f => Paths.get(f.getAbsolutePath)) }
    else IO(Stream.Empty)

  override def run(args: List[String]): IO[ExitCode] = {
    def isDirPath(s: String): Boolean = {
      val f = new File(s)
      f.exists && f.isDirectory }

    def printLoop[T](buffer: BufferedZipper[T]): IO[Unit] = for {
      _          <- printlnSafe(buffer)
      _          <- printlnSafe("[n]ext or [p]revious item?")
      np         <- readUntil(Set("n", "p"), "[n] = next, [p] = previous")
      nextBuffer =  if (np == "n") buffer.next
                    else           buffer.prev
      _          <- printLoop[T](nextBuffer.get) //TODO get
    } yield Unit

    def userInterface: IO[ExitCode] = for {
      _      <- printlnSafe("Path with pictures:")
      path   <- readUntil(isDirPath, "path must be an absolute path to a directory. try again")
      file   <- IO(new File(path)) // TODO I honestly have no idea when Java makes the system calls
      count  <- IO(file.listFiles.length) // TODO don't do this twice
      _      <- printlnSafe(s"path contains $count files")
      files  <- getFiles(file)
      stream =  shuffle(files).eval(new scala.util.Random(System.nanoTime()))
      _      <- printlnSafe("first random pic: ")
      buffer <- BufferedZipper(stream, Some(1000L)).fold[IO[BufferedZipper[Path]]](IO.raiseError(new Exception("Directory has no files")))(IO(_)) // TODO. This is CHEAP
      _      <- printLoop(buffer)
    } yield ExitCode.Success

    userInterface
  }
}