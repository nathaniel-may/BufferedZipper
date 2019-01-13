package util

import cats.effect.IO

object CommandLine {

  def readUntil(acceptable: String => Boolean, errorMessage: String): IO[String] =
    readLineSafe.flatMap {
      line => if(!acceptable(line))
                printlnSafe(errorMessage)
                  .flatMap { _ => readUntil(acceptable, errorMessage) }
              else IO(line) }

  def printSafe  (a: Any): IO[Unit] = IO(print(a))
  def printlnSafe(a: Any): IO[Unit] = IO(println(a))
  def readLineSafe = IO(scala.io.StdIn.readLine())
}
