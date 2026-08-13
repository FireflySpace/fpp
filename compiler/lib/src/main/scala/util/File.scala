package fpp.compiler.util

import java.io.{ByteArrayOutputStream, FileReader}
import java.nio.file.Files

/** A file used in compilation */
sealed trait File {

  override def toString = this match {
    case File.Path(p) => p.normalize().toString
    case File.StdIn => "stdin"
  }

  /** Open the file for reading */
  def openRead(locOpt: Option[Location] = None): Result.Result[Array[Char]] = {
    try {
      val r = this match {
        case File.Path(p) => scala.io.Source.fromFile(p.toFile)
        case File.StdIn => scala.io.Source.fromInputStream(System.in)
      }

      Right(r.toArray)
    }
    catch {
      case e: Exception => Left(FileError.CannotOpen(locOpt, this.toString))
    }
  }

  /** Open the file for writing */
  def openWrite(locOpt: Option[Location] = None): Result.Result[java.io.PrintWriter] = {
    try {
      this match {
        case File.Path(p) => {
          val overwrite = false
          val fileWriter = new java.io.FileWriter(p.toFile, overwrite)
          val printWriter = new java.io.PrintWriter(fileWriter)
          Right(printWriter)
        }
        case _ => Left(FileError.CannotOpen(locOpt, this.toString)
        )
      }
    }
    catch {
      case e: Exception => Left(FileError.CannotOpen(locOpt, this.toString))
    }
  }

}

object File {

  type JavaPath = java.nio.file.Path

  /** A file path */
  final case class Path(path: JavaPath) extends File

  /** Standard input */
  case object StdIn extends File

  /** Get the Java path associated with a string */
  def getJavaPath(s: String): JavaPath =
    java.nio.file.Paths.get(s).toAbsolutePath.normalize

  /** Construct a file from a string representing a file path */
  def fromString(s: String): File = Path(getJavaPath(s))

  /** Relativize a path without relying on Path.relativize.
   *
   *  Scala Native 0.4.17 drops parent segments when the target is outside the
   *  base directory. Its Path.normalize also cancels consecutive leading `..`
   *  segments.
   */
  def relativize(base: JavaPath)(target: JavaPath): String = {
    val normalizedBase = base.toAbsolutePath.normalize
    val normalizedTarget = target.toAbsolutePath.normalize
    if normalizedBase.getRoot != normalizedTarget.getRoot
    then throw new IllegalArgumentException(
      s"cannot relativize paths with different roots: $normalizedBase and $normalizedTarget"
    )

    def getNames(path: JavaPath) =
      (0 until path.getNameCount).map(path.getName(_).toString)

    val baseNames = getNames(normalizedBase)
    val targetNames = getNames(normalizedTarget)
    val commonCount = baseNames.zip(targetNames).takeWhile {
      case (baseName, targetName) => baseName == targetName
    }.length
    val relativeNames =
      Seq.fill(baseNames.length - commonCount)("..") ++ targetNames.drop(commonCount)
    if relativeNames.isEmpty then "." else relativeNames.mkString("/")
  }

  /** Remove the longest prefix from a Java path */
  def removeLongestPrefix(prefixes: List[String])(path: JavaPath): JavaPath = {
    def removePrefix(s: String) = {
      val prefix = java.nio.file.Paths.get(s)
      if (path.startsWith(prefix)) prefix.relativize(path) else path
    }

    prefixes.map(removePrefix(_)) match {
      case Nil => path
      case head :: tail => {
        def min(p1: JavaPath, p2: JavaPath) =
          if (p1.getNameCount < p2.getNameCount) p1 else p2

        tail.fold(head)(min)
      }
    }
  }

}
