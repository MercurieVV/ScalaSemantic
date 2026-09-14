#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::3.1.4

import java.io.File
import java.nio.file.*
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex
import java.util.regex.Pattern
import scala.sys.process.Process

object CompareGrep {
  val excludedDirs = Set(
    "target",
    "out",
    ".git",
    ".idea",
    ".bsp",
    ".scala-build",
    ".gemini",
    ".worktrees",
    "worktrees"
  )

  def findScalaFiles(dir: Path): Seq[Path] = {
    if (Files.exists(dir)) {
      val stream = Files.walk(dir)
      try {
        stream
          .iterator()
          .asScala
          .filter(path => {
            val relPath = dir.relativize(path)
            val elements = relPath.iterator().asScala.map(_.toString).toSet
            elements.intersect(excludedDirs).isEmpty && Files.isRegularFile(path) && path.toString
              .endsWith(".scala")
          })
          .toList
      } finally {
        stream.close()
      }
    } else {
      Seq.empty
    }
  }

  // Run a git command and return its output
  def runCmd(cmd: Seq[String]): Option[String] = {
    try {
      val out = Process(cmd).!!
      Some(out.trim)
    } catch {
      case _: Exception => None
    }
  }

  // Retrieve file content from git history if deleted
  def getFileFromGit(uri: String): Option[String] = {
    runCmd(Seq("git", "log", "-n", "1", "--pretty=format:%H", "--", uri)).flatMap { commit =>
      if (commit.isEmpty) None
      else {
        runCmd(Seq("git", "show", s"$commit:$uri")).orElse {
          runCmd(Seq("git", "show", s"$commit~1:$uri"))
        }
      }
    }
  }

  // Extract simple name from SemanticDB symbol
  def extractSimpleName(symbol: String): String = {
    val parts = symbol.split("[/#\\.\\(\\)\\[\\]]+").filter(_.nonEmpty)
    parts.lastOption.getOrElse(symbol)
  }

  // Format line as standard grep output: filename:lineNum:lineContent\n
  def formatGrepLine(relPath: String, lineNum: Int, lineContent: String): String = {
    s"$relPath:$lineNum:$lineContent\n"
  }

  def main(args: Array[String]): Unit = {
    val logFile = Paths.get("scala-semantic-mcp.log")
    if (!Files.exists(logFile)) {
      println("Error: scala-semantic-mcp.log not found in the current directory.")
      sys.exit(1)
    }

    val projectRoot = Paths.get(".").toAbsolutePath.normalize()
    val scalaFiles = findScalaFiles(projectRoot)

    // Initialize virtual files cache with files from disk
    val filesCache = scala.collection.mutable.Map[String, (Seq[String], String)]()
    scalaFiles.foreach { path =>
      val relPath = projectRoot.relativize(path).toString
      val content = new String(Files.readAllBytes(path), "UTF-8")
      val lines = content.split("\r?\n").toSeq
      filesCache(relPath) = (lines, content)
    }

    val lines = Files.readAllLines(logFile).asScala.toList

    val CallPattern = """.*?call\s+([a-zA-Z0-9_]+)\s+(\{.*)""".r
    val OutPattern = """.*?out\s+(\{.*)""".r

    // Helper to resolve and cache file content dynamically
    def ensureFileCached(uri: String): Unit = {
      if (uri.nonEmpty && !filesCache.contains(uri)) {
        // 1. Try Git history
        getFileFromGit(uri) match {
          case Some(content) =>
            val fileLines = content.split("\r?\n").toSeq
            filesCache(uri) = (fileLines, content)
          case None =>
            // 2. Try disk directly
            val path = projectRoot.resolve(uri)
            if (Files.exists(path)) {
              val content = new String(Files.readAllBytes(path), "UTF-8")
              val fileLines = content.split("\r?\n").toSeq
              filesCache(uri) = (fileLines, content)
            }
        }
      }
    }

    // Pre-populate virtual files cache from log entries where found: true
    var j = 0
    while (j < lines.length - 1) {
      lines(j) match {
        case CallPattern(toolName, paramsStr) if toolName == "annotated_source" =>
          val nextLine = lines(j + 1)
          nextLine match {
            case OutPattern(outJsonStr) =>
              try {
                val params = ujson.read(paramsStr).obj
                val uri = params.get("uri").map(_.str).getOrElse("")
                val format = params.get("format").map(_.str).getOrElse("annotated")

                if (uri.nonEmpty) {
                  ensureFileCached(uri)

                  if (!filesCache.contains(uri)) {
                    val outJson = ujson.read(outJsonStr)
                    outJson.obj.get("result").flatMap { r =>
                      r.obj.get("content").flatMap { c =>
                        c.arr.headOption.flatMap { item =>
                          item.obj.get("text").map { textStr =>
                            val innerJson = ujson.read(textStr)
                            if (innerJson.obj.get("found").exists(_.bool)) {
                              val content = innerJson("content").str
                              if (format == "plain") {
                                val fileLines = content.split("\r?\n").toSeq
                                filesCache(uri) = (fileLines, content)
                              } else {
                                // Strip gutter
                                val plainLines = content.split("\r?\n").toSeq.map { line =>
                                  line.replaceFirst("""^\s*\d+\s*(\|\s*)?""", "")
                                }
                                val plainContent = plainLines.mkString("\n")
                                filesCache(uri) = (plainLines, plainContent)
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              } catch {
                case _: Exception => // ignore parsing errors
              }
            case _ =>
          }
        case _ =>
      }
      j += 1
    }

    case class LogEntry(
        toolName: String,
        paramsStr: String,
        mcpSize: Int,
        grepSize: Int
    )

    val entries = scala.collection.mutable.ListBuffer[LogEntry]()

    var i = 0
    while (i < lines.length - 1) {
      lines(i) match {
        case CallPattern(toolName, paramsStr) =>
          val nextLine = lines(i + 1)
          nextLine match {
            case OutPattern(outJsonStr) =>
              val params = try {
                ujson.read(paramsStr).obj
              } catch {
                case _: Exception => ujson.Obj().obj
              }

              params.get("uri").map(_.str).foreach(ensureFileCached)

              val mcpSize = try {
                val outJson = ujson.read(outJsonStr)
                outJson.obj
                  .get("result")
                  .flatMap { r =>
                    r.obj.get("content").flatMap { c =>
                      c.arr.headOption.flatMap { item =>
                        item.obj.get("text").map(_.str.length)
                      }
                    }
                  }
                  .getOrElse(outJsonStr.length)
              } catch {
                case _: Exception => outJsonStr.length
              }

              val grepSize = computeGrepSize(toolName, params, filesCache.toMap)

              entries += LogEntry(toolName, paramsStr, mcpSize, grepSize)
              i += 2

            case _ =>
              i += 1
          }
        case _ =>
          i += 1
      }
    }

    // Print table
    val headers =
      Seq("Tool Name", "Params Preview", "MCP Size (chars)", "Grep Size (chars)", "Saving Ratio")
    val rows: List[Seq[String]] = entries.map { entry =>
      val paramPreview =
        if (entry.paramsStr.length > 50) entry.paramsStr.take(47) + "..." else entry.paramsStr
      val ratio =
        if (entry.grepSize == 0) "N/A"
        else f"${(1.0 - entry.mcpSize.toDouble / entry.grepSize) * 100.0}%.1f%%"
      Seq(entry.toolName, paramPreview, entry.mcpSize.toString, entry.grepSize.toString, ratio)
    }.toList

    val totalMcp = entries.map(_.mcpSize).sum
    val totalGrep = entries.map(_.grepSize).sum
    val totalRatio =
      if (totalGrep == 0) "N/A" else f"${(1.0 - totalMcp.toDouble / totalGrep) * 100.0}%.1f%%"
    val totalRow =
      Seq("Total", s"${entries.size} calls", totalMcp.toString, totalGrep.toString, totalRatio)

    val allRows: List[Seq[String]] = rows :+ totalRow

    val colWidths: Seq[Int] = (headers :: allRows).transpose.map { (col: Seq[String]) =>
      col.map(_.length).max
    }

    def printRow(row: Seq[String]): Unit = {
      val formatted = row
        .zip(colWidths)
        .map { case (cell, width) =>
          cell.padTo(width, ' ')
        }
        .mkString(" | ")
      println(formatted)
    }

    val totalWidth = colWidths.sum + 3 * (colWidths.size - 1)
    println("-" * totalWidth)
    printRow(headers)
    println("-" * totalWidth)
    rows.foreach(printRow)
    println("-" * totalWidth)
    printRow(totalRow)
    println("-" * totalWidth)
  }

  def computeGrepSize(
      toolName: String,
      params: scala.collection.mutable.Map[String, ujson.Value],
      filesCache: Map[String, (Seq[String], String)]
  ): Int = {
    toolName match {
      case "annotated_source" =>
        val uri = params.get("uri").map(_.str).getOrElse("")
        filesCache.get(uri).map(_._2.length).getOrElse(0)

      case "document_outline" =>
        val uri = params.get("uri").map(_.str).getOrElse("")
        filesCache
          .get(uri)
          .map { case (lines, _) =>
            val defPattern = """\b(class|trait|object|def|val|var|type)\s""".r
            lines.zipWithIndex.collect {
              case (line, idx) if defPattern.findFirstIn(line).isDefined =>
                formatGrepLine(uri, idx + 1, line).length
            }.sum
          }
          .getOrElse(0)

      case "find_symbol" =>
        val query = params.get("query").orElse(params.get("name")).map(_.str).getOrElse("")
        if (query.isEmpty) 0
        else {
          filesCache.map { case (relPath, (lines, _)) =>
            lines.zipWithIndex.collect {
              case (line, idx) if line.contains(query) =>
                formatGrepLine(relPath, idx + 1, line).length
            }.sum
          }.sum
        }

      case "find_usages" =>
        val symbol = params.get("symbol").map(_.str).getOrElse("")
        if (symbol.isEmpty) 0
        else {
          val name = extractSimpleName(symbol)
          val isWord = name.forall(c => c.isLetterOrDigit || c == '_')
          val regex = if (isWord) s"\\b${Pattern.quote(name)}\\b".r else Pattern.quote(name).r
          filesCache.map { case (relPath, (lines, _)) =>
            lines.zipWithIndex.collect {
              case (line, idx) if regex.findFirstIn(line).isDefined =>
                formatGrepLine(relPath, idx + 1, line).length
            }.sum
          }.sum
        }

      case "method_signature" =>
        val symbol = params.get("symbol").map(_.str).getOrElse("")
        if (symbol.isEmpty) 0
        else {
          val name = extractSimpleName(symbol)
          val regex = s"\\b(def|val|var)\\s+${Pattern.quote(name)}\\b".r
          filesCache.map { case (relPath, (lines, _)) =>
            lines.zipWithIndex.collect {
              case (line, idx) if regex.findFirstIn(line).isDefined =>
                formatGrepLine(relPath, idx + 1, line).length
            }.sum
          }.sum
        }

      case "class_hierarchy" =>
        val symbol = params.get("symbol").map(_.str).getOrElse("")
        if (symbol.isEmpty) 0
        else {
          val name = extractSimpleName(symbol)
          val regexDef = s"\\b(class|trait|object)\\s+${Pattern.quote(name)}\\b".r
          val regexExt = s"\\b(extends|with)\\s+${Pattern.quote(name)}\\b".r
          filesCache.map { case (relPath, (lines, _)) =>
            lines.zipWithIndex.collect {
              case (line, idx)
                  if regexDef.findFirstIn(line).isDefined || regexExt.findFirstIn(line).isDefined =>
                formatGrepLine(relPath, idx + 1, line).length
            }.sum
          }.sum
        }

      case "members" =>
        val symbol = params.get("symbol").map(_.str).getOrElse("")
        if (symbol.isEmpty) 0
        else {
          val name = extractSimpleName(symbol)
          val defRegex = s"\\b(class|trait|object)\\s+${Pattern.quote(name)}\\b".r
          val targetFiles = filesCache
            .filter { case (_, (_, content)) =>
              defRegex.findFirstIn(content).isDefined
            }
            .keys
            .toList

          targetFiles.map { uri =>
            filesCache(uri)._1.zipWithIndex.collect {
              case (line, idx)
                  if """\b(class|trait|object|def|val|var|type)\s""".r
                    .findFirstIn(line)
                    .isDefined =>
                formatGrepLine(uri, idx + 1, line).length
            }.sum
          }.sum
        }

      case "structure" =>
        filesCache.map { case (relPath, (lines, _)) =>
          lines.zipWithIndex.collect {
            case (line, idx)
                if """\b(class|trait|object|package)\s""".r.findFirstIn(line).isDefined =>
              formatGrepLine(relPath, idx + 1, line).length
          }.sum
        }.sum

      case _ =>
        0
    }
  }
}
