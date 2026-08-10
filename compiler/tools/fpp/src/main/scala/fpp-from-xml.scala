package fpp.compiler.tools

import fpp.compiler.codegen._
import fpp.compiler.util._
import scopt.OParser

object FPPFromXml {

  case class Options(
    files: List[File] = Nil,
  )

  def command(options: Options): Result.Result[Unit] = {
    for {
      xmlFiles <- Result.map(options.files, parseXmlFile)
      lines <- XmlFppWriter.writeFileList(xmlFiles)
    }
    yield lines.map(Line.write(Line.stdout))
  }

  def parseXmlFile(file: File): Result.Result[XmlFppWriter.File] = {
    for {
      elem <- try {
        val source = scala.io.Source.fromFile(file.toString)
        val input = try {
          source.mkString
        }
        finally source.close()
        val xml = input.dropWhile(_.isWhitespace)
        if (xml.startsWith("<")) {
          val parser =
            scala.xml.parsing.ConstructingParser.fromSource(
              scala.io.Source.fromString(xml),
              preserveWS = true
            )
          Option(parser.document().docElem)
            .collect { case elem: scala.xml.Elem => elem }
            .toRight(XmlError.ParseError(file.toString, "XML document has no root element"))
        }
        else Left(
          XmlError.ParseError(
            file.toString,
            "org.xml.sax.SAXParseException; lineNumber: 1; columnNumber: 1; " +
              "Content is not allowed in prolog."
          )
        )
      }
      catch {
        case e: Exception => Left(XmlError.ParseError(file.toString, e.toString))
      }
    }
    yield XmlFppWriter.File(file.toString, elem)
  }

  def toolMain(args: Array[String]) =
    Tool(name).mainMethod(args, oparser, Options(), command)

  val builder = OParser.builder[Options]

  val name = "fpp-from-xml"

  val oparser = {
    import builder._
    OParser.sequence(
      programName(name),
      head(name, Version.v),
      help('h', "help").text("print this message and exit"),
      arg[String]("file ...")
        .unbounded()
        .action((f, c) => c.copy(files = File.fromString(f) :: c.files))
        .text("files to translate"),
    )
  }

}
