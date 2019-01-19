package com.nathanielmay.randpix

// Java
import java.io.File
import java.nio.file.{Path, Paths}

// Scala
import cats.effect.{ExitCode, IO, IOApp}

// Project
import util.Shuffle.shuffle
import util.BufferedStream
import util.CommandLine.{printlnSafe, readUntil}

object RandPix extends IOApp {

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

    def printFocus[T](buffer: BufferedStream[T], otherwise: String): IO[Unit] =
      printlnSafe(buffer.focus.getOrElse(otherwise))

    def printLoop[T](buffer: BufferedStream[T]): IO[Unit] = for {
      _          <- printFocus(buffer, "Error: nothing to print")
      _          <- printlnSafe(s"BUFFER: ${buffer.buff}")
      _          <- printlnSafe("[n]ext or [p]revious item?")
      np         <- readUntil(Set("n", "p"), "[n] = next, [p] = previous")
      nextBuffer =  if (np == "n") buffer.next
                    else           buffer.prev
      _          <- printLoop[T](nextBuffer)
    } yield Unit

    def userInterface: IO[ExitCode] = for {
      _      <- printlnSafe("Path with pictures:")
      path   <- readUntil(isDirPath, "path must be an absolute path to a directory. try again")
      file   <- IO(new File(path)) // TODO I honestly have no idea when Java makes the system calls
      count  <- IO(file.listFiles.length)
      _      <- printlnSafe(s"path contains $count files")
      files  <- getFiles(file)
      stream =  shuffle(files).eval(new scala.util.Random(System.nanoTime())) //TODO do I want that here?
      _      <- printlnSafe("first random pic: ")
      _      <- printLoop(BufferedStream(stream))
    } yield ExitCode.Success

    userInterface
  }
}
