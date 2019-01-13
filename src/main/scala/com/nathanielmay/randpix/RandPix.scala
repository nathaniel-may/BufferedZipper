package com.nathanielmay.randpix

import java.io.File
import java.nio.file.{Path, Paths}
import java.util.concurrent.Executors

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import fs2.{io, text}
import util.Shuffle.shuffle
import util.BufferedStream, BufferedStream.next

import scala.concurrent.ExecutionContext

object RandPix {

  def getFiles(f: File): Stream[Path] =
    if (f.isDirectory) f.listFiles()
      .toStream
      .filter(!_.isDirectory)
      .map(f => Paths.get(f.getAbsolutePath))
    else throw new Exception("not a valid directory") // TODO

//  def next(pos: Int, buffer: Vector[Path], stream: Stream[Path]): (Int, Vector[Path], Stream[Path], Option[Path]) =
//    if (pos+1 < buffer.size) (pos+1, buffer, stream, buffer.get(pos+1))
//    else stream match {
//      case Stream.Empty => (pos,   buffer,      stream, buffer.get(pos))
//      case p #:: ps     => (pos+1, buffer :+ p, ps,     Some(p))
//    }
//
//  def prev(pos: Int, buffer: Vector[Path], stream: Stream[Path]): (Int, Vector[Path], Stream[Path], Option[Path]) =
//    if (pos > 0) (pos-1, buffer, stream, buffer.get(pos-1))
//    else (pos, buffer, stream, buffer.headOption)

  def main(args: Array[String]): Unit = {
    println("Path with pictures:")
    val path = scala.io.StdIn.readLine()
    val file = new File(path)
    println(s"path contains ${file.listFiles.length} files")

    val shuffled: Stream[Path] = shuffle(getFiles(file)).eval(new scala.util.Random(System.nanoTime()))
    val buffer   = Vector()
    val pos      = 0

    def printLoop[T](buffer: BufferedStream[T]): Unit = {
      println("[n]ext or [p]revious item?")
      var line = scala.io.StdIn.readLine()
      while(!List("n", "p").contains(line)){
        println("n = next, p = previous")
        line = scala.io.StdIn.readLine()
      }
    }

    println(s"first file: ${next.eval(BufferedStream(shuffled))}") // TODO

//    for {
//      file <- IO {
//        new File(args(0).toString)
//      }
//      files =  getFiles(file)
//      shuffled <- shuffle(files)
//    } yield Unit
  }
}
