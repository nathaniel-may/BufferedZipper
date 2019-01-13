package util

import cats.effect.IO

object CommandLine {

  def readUntil(acceptable: List[String], errorMessage: String): IO[String] =
    IO(scala.io.StdIn.readLine())
    .flatMap { line => if(!acceptable.contains(line))
                         IO(println(errorMessage))
                           .flatMap { _ => readUntil(acceptable, errorMessage) }
                       else IO(line) }
}
