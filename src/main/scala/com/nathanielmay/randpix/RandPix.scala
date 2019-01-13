package com.nathanielmay.randpix

// Java
import java.io.File
import java.nio.file.{Path, Paths}

// Scala
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._

// Project
import util.Shuffle.shuffle
import util.BufferedStream, BufferedStream.{next, prev}
import util.CommandLine, CommandLine.{printlnSafe, readLineSafe}

object RandPix extends IOApp {

  def getFiles(f: File): IO[Stream[Path]] =
    if (f.isDirectory) IO {
      f.listFiles()
        .toStream
        .filter(!_.isDirectory)
        .map(f => Paths.get(f.getAbsolutePath)) }
    else IO(Stream.Empty)

  override def run(args: List[String]): IO[ExitCode] = {
    def printLoop[T](buffer: BufferedStream[T]): IO[Unit] = for {
      _                   <- printlnSafe("[n]ext or [p]revious item?")
      np                  <- CommandLine.readUntil(List("n", "p"), "[n] = next, [p] = previous")
      tuple               =  if (np == "n") next[T].run(buffer)
                             else           prev[T].run(buffer)
      (nextBuffer, value) =  tuple
      _                   <- printlnSafe(value.getOrElse("Error: nothing to print"))
      _                   <- printLoop[T](nextBuffer)
    } yield Unit

    def userInterface: IO[ExitCode] = for {
      _      <- printlnSafe("Path with pictures:")
      path   <- readLineSafe
      file   <- IO(new File(path)) // TODO I honestly have no idea when Java makes the system calls
      _      <- printlnSafe(s"path contains ${file.listFiles.length} files") // TODO impure
      files  <- getFiles(file)
      stream =  shuffle(files).eval(new scala.util.Random(System.nanoTime())) //TODO do I want that here?
      _      <- printLoop(BufferedStream(stream))
    } yield ExitCode.Success

    userInterface
  }
}
