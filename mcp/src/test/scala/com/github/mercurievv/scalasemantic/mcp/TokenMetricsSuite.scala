package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Reproducible token-cost comparison for representative MCP tool queries versus the raw
  * grep/file-read context an agent would otherwise need to consume.
  */
class TokenMetricsSuite extends munit.FunSuite:

  private val root = Path.of(".").toAbsolutePath.nn.normalize().nn
  private val tools = McpTools.all(Analyzer(SemanticIndex.fromProject(".")), root)

  private val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"
  private val Render = "com/github/mercurievv/scalasemantic/fixtures/Sample.render()."
  private val Show = "com/github/mercurievv/scalasemantic/fixtures/Show#"
  private def calls(name: String) =
    s"com/github/mercurievv/scalasemantic/fixtures/Calls.$name()."

  private val fixtureUri =
    "analysis/src/test/scala/com/github/mercurievv/scalasemantic/fixtures/Sample.scala"
  private val jsonPath = root.resolve("docs/research/token-metrics.json").nn
  private val markdownPath = root.resolve("docs/research/token-metrics-methodology.md").nn

  private def req(method: String, params: ujson.Value): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> method, "params" -> params)

  private def call(name: String, args: ujson.Value): String =
    val response =
      Mcp
        .handle(req("tools/call", ujson.Obj("name" -> name, "arguments" -> args)), tools)
        .getOrElse(fail(s"no response for $name"))
    response("result")("content")(0)("text").str

  private case class QueryMetric(
      id: String,
      query: String,
      tool: String,
      baseline: String,
      toolOutput: String,
      baselineOutput: String
  ):
    val toolChars: Int = toolOutput.length
    val baselineChars: Int = baselineOutput.length
    val toolTokens: Int = approxTokens(toolOutput)
    val baselineTokens: Int = approxTokens(baselineOutput)
    val tokenDelta: Int = baselineTokens - toolTokens
    val savingsPercent: Double =
      if baselineTokens == 0 then 0.0
      else
        BigDecimal(tokenDelta.toDouble * 100.0 / baselineTokens)
          .setScale(1, BigDecimal.RoundingMode.HALF_UP)
          .toDouble

  private def approxTokens(text: String): Int =
    math.max(1, math.ceil(text.length.toDouble / 4.0).toInt)

  private def source(uri: String): String =
    Files.readString(root.resolve(uri)).nn

  private def grep(pattern: String, paths: Seq[String]): String =
    paths
      .flatMap { uri =>
        source(uri).linesIterator.zipWithIndex.collect {
          case (line, index) if line.contains(pattern) => s"$uri:${index + 1}:$line"
        }
      }
      .mkString("\n")

  private def scalaFilesUnder(paths: Seq[String]): Seq[String] =
    paths.flatMap { p =>
      val start = root.resolve(p).nn
      Files
        .walk(start)
        .iterator()
        .asScala
        .filter(path =>
          Files.isRegularFile(path) && path.getFileName.nn.toString.endsWith(".scala")
        )
        .map(path => root.relativize(path.toAbsolutePath.nn.normalize().nn).nn.toString)
        .toSeq
        .sorted
    }

  private def fixtureWithHeader(reason: String): String =
    s"// baseline: $reason\n" + source(fixtureUri)

  private lazy val queries: List[QueryMetric] =
    val fixtureSources = scalaFilesUnder(
      Seq("analysis/src/test/scala", "compat-fixtures/src/main/scala-3")
    )
    List(
      QueryMetric(
        id = "find-usages-animal",
        query = "Find all definitions and references of the Animal trait.",
        tool = "find_usages",
        baseline = "Text grep for Animal across fixture source trees.",
        toolOutput = call("find_usages", ujson.Obj("symbol" -> Animal)),
        baselineOutput = grep("Animal", fixtureSources)
      ),
      QueryMetric(
        id = "class-hierarchy-animal",
        query = "Identify Animal parents, linearization, and known subtypes.",
        tool = "class_hierarchy",
        baseline = "Read the fixture source containing Animal, Dog, and Fish inheritance.",
        toolOutput = call("class_hierarchy", ujson.Obj("symbol" -> Animal, "detailed" -> true)),
        baselineOutput = fixtureWithHeader("inspect trait/class declarations and extends clauses")
      ),
      QueryMetric(
        id = "method-signature-render",
        query = "Recover Sample.render's full method signature, including using parameters.",
        tool = "method_signature",
        baseline = "Read the fixture source and infer the complete signature from text.",
        toolOutput = call("method_signature", ujson.Obj("symbol" -> Render, "detailed" -> true)),
        baselineOutput = fixtureWithHeader("locate render and surrounding type-class context")
      ),
      QueryMetric(
        id = "trace-implicit-show",
        query = "Trace givens that produce Show and their dependencies.",
        tool = "trace_implicit_chain",
        baseline = "Read the fixture source and follow given definitions manually.",
        toolOutput = call("trace_implicit_chain", ujson.Obj("type" -> Show)),
        baselineOutput = fixtureWithHeader("inspect Show, intShow, and listShow dependencies")
      ),
      QueryMetric(
        id = "call-path-a-to-c",
        query = "Find the call path from Calls.a to Calls.c.",
        tool = "call_path",
        baseline = "Read the fixture source and follow method bodies manually.",
        toolOutput = call(
          "call_path",
          ujson.Obj("from" -> calls("a"), "to" -> calls("c"), "detailed" -> true)
        ),
        baselineOutput = fixtureWithHeader("inspect Calls.a, Calls.b, and Calls.c bodies")
      )
    )

  private def summary(metrics: List[QueryMetric]): (Int, Int, Int, Double) =
    val tool = metrics.map(_.toolTokens).sum
    val baseline = metrics.map(_.baselineTokens).sum
    val delta = baseline - tool
    val savings =
      BigDecimal(delta.toDouble * 100.0 / baseline)
        .setScale(1, BigDecimal.RoundingMode.HALF_UP)
        .toDouble
    (tool, baseline, delta, savings)

  private def json(metrics: List[QueryMetric]): String =
    val (tool, baseline, delta, savings) = summary(metrics)
    ujson
      .Obj(
        "method" -> "Approximate token proxy: ceil(character_count / 4).",
        "baselineScope" -> "Raw source/grep context is generated from checked-in fixture sources.",
        "summary" -> ujson.Obj(
          "queryCount" -> metrics.size,
          "toolTokens" -> tool,
          "baselineTokens" -> baseline,
          "tokenDelta" -> delta,
          "savingsPercent" -> savings
        ),
        "queries" -> ujson.Arr.from(metrics.map { m =>
          ujson.Obj(
            "id" -> m.id,
            "query" -> m.query,
            "tool" -> m.tool,
            "baseline" -> m.baseline,
            "toolChars" -> m.toolChars,
            "baselineChars" -> m.baselineChars,
            "toolTokens" -> m.toolTokens,
            "baselineTokens" -> m.baselineTokens,
            "tokenDelta" -> m.tokenDelta,
            "savingsPercent" -> m.savingsPercent
          )
        })
      )
      .render(indent = 2) + "\n"

  private val beginMarker = "<!-- BEGIN AUTO-GENERATED -->"
  private val endMarker = "<!-- END AUTO-GENERATED -->"

  private def getTableBetweenMarkers(content: String): String =
    val beginIdx = content.indexOf(beginMarker)
    val endIdx = content.indexOf(endMarker)
    if beginIdx == -1 || endIdx == -1 || endIdx <= beginIdx then
      fail("Could not find auto-generated markers in token-metrics-methodology.md")
    content.substring(beginIdx + beginMarker.length, endIdx).trim

  private def replaceTableBetweenMarkers(content: String, newTable: String): String =
    val beginIdx = content.indexOf(beginMarker)
    val endIdx = content.indexOf(endMarker)
    if beginIdx == -1 || endIdx == -1 || endIdx <= beginIdx then
      fail("Could not find auto-generated markers in token-metrics-methodology.md")
    val prefix = content.substring(0, beginIdx + beginMarker.length)
    val suffix = content.substring(endIdx)
    s"$prefix\n\n$newTable\n\n$suffix"

  private def markdownTable(metrics: List[QueryMetric]): String =
    val (tool, baseline, delta, savings) = summary(metrics)
    val rows = metrics.map { m =>
      s"| `${m.id}` | `${m.tool}` | ${m.toolTokens} | ${m.baselineTokens} | ${m.tokenDelta} | ${m.savingsPercent}% |"
    }
    List(
      "| Query | MCP tool | Tool tokens | Baseline tokens | Delta | Savings |",
      "| --- | --- | ---: | ---: | ---: | ---: |",
      rows.mkString("\n"),
      s"| **Overall (${metrics.size} queries)** | | **$tool** | **$baseline** | **$delta** | **$savings%** |"
    ).mkString("\n")

  test("token metrics artifacts match regenerated MCP-vs-baseline comparison") {
    val generatedJson = json(queries)
    val generatedTable = markdownTable(queries)

    if sys.env.get("UPDATE_TOKEN_METRICS").contains("1") then
      Files.writeString(jsonPath, generatedJson)
      val existingMarkdown = Files.readString(markdownPath).nn
      val updatedMarkdown = replaceTableBetweenMarkers(existingMarkdown, generatedTable)
      Files.writeString(markdownPath, updatedMarkdown)
    else
      assertEquals(Files.readString(jsonPath).nn, generatedJson, "token metrics JSON is stale")
      val existingMarkdown = Files.readString(markdownPath).nn
      val existingTable = getTableBetweenMarkers(existingMarkdown)
      assertEquals(
        existingTable,
        generatedTable.trim,
        "token metrics markdown table is stale"
      )
  }
