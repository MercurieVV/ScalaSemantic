package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import java.nio.file.{Files, Paths}

object ToolCli:
  def main(args: Array[String]): Unit =
    val m = parse(args)
    val indexDir = m("index")
    val toolName = m("tool")
    val argsJson = ujson.read(m.getOrElse("args", "{}"))
    val root = Paths.get(m.getOrElse("root", "."))

    val index = SemanticIndex.fromRoots(Seq(Paths.get(indexDir)))

    // Runs the named tool against `analyzer` and returns the pretty JSON. Kept as a local so the
    // presentation-compiler branch can call it INSIDE `useCurrentJvm` — the PC backend is torn down
    // when that block returns, so the tool must run before then (running it after threw
    // CancellationException).
    def runTool(analyzer: Analyzer, args: ujson.Value): String =
      val tools = McpTools.all(analyzer, root)
      val tool = tools
        .find(_.name == toolName)
        .getOrElse(sys.error(s"unknown tool: $toolName (have: ${tools.map(_.name).mkString(",")})"))
      ujson.write(tool.run(args), indent = 2)

    val output = m.get("source") match
      case Some(sourcePath) =>
        val uri = m("uri")
        val sourceText = new String(Files.readAllBytes(Paths.get(sourcePath)))
        argsJson("source") = sourceText
        argsJson("uri") = uri
        PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
          runTool(Analyzer(index, Some(backend)), argsJson)
        }
      case None =>
        runTool(Analyzer(index), argsJson)

    println(output)

  private def parse(args: Array[String]): Map[String, String] =
    def loop(idx: Int, acc: Map[String, String]): Map[String, String] =
      if idx >= args.length then acc
      else if args(idx).startsWith("--") then
        val key = args(idx).drop(2)
        if idx + 1 < args.length && !args(idx + 1).startsWith("--") then
          loop(idx + 2, acc + (key -> args(idx + 1)))
        else loop(idx + 1, acc + (key -> ""))
      else loop(idx + 1, acc)
    loop(0, Map.empty)
