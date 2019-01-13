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

  def getFiles(f: File): Stream[Path] =
    if (f.isDirectory) f.listFiles()
      .toStream
      .filter(!_.isDirectory)
      .map(f => Paths.get(f.getAbsolutePath))
    else throw new Exception("not a valid directory") // TODO

  override def run(args: List[String]): IO[ExitCode] = {
    def printLoop[T](buffer: BufferedStream[T]): IO[Unit] = for {
      _  <- IO(println("[n]ext or [p]revious item?"))
      np <- CommandLine.readUntil(List("n", "p"), "[n] = next, [p] = previous")
      _  <- if (np == "n") next.run(buffer)
      else prev.run(buffer)
    } yield Unit

    def userInterface: IO[Unit] = for {
      _      <- printlnSafe("Path with pictures:")
      path   <- readLineSafe
      file   =  new File(path)
      _      <- printlnSafe(s"path contains ${file.listFiles.length} files")
      stream =  shuffle(getFiles(file)).eval(new scala.util.Random(System.nanoTime())) //TODO do I want that here?
      _      <- printLoop(BufferedStream(stream))
    } yield Unit

    userInterface.map(_ => ExitCode.Success)
  }
}
