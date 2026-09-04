package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Paths

/** `source_ranges` end-to-end against this repo's own SemanticDB — dogfooding, same pattern as
  * `McpSuite`/`TokenMetricsSuite`. Structural assertions only (no golden lock): this repo's own
  * source shifts over time (scalafmt reformatting included), so every assertion pins line 1 (a
  * `package` statement, stable regardless of reformatting further down the file) rather than exact
  * line content deeper in a file.
  */
class SourceRangesFqnSuite extends munit.FunSuite:

  private val root = Paths.get(".").nn
  private val tools = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private def call(args: ujson.Value): ujson.Value =
    tools.find(_.name == "source_ranges").getOrElse(fail("no source_ranges")).run(args)

  private val RangeSelectorUri =
    "analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelector.scala"
  private val RangeSelectorPropertySuiteUri =
    "analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelectorPropertySuite.scala"

  test("resolves a dotted FQN target to its defining file and renders the requested range"):
    val res = call(
      ujson.Obj("query" -> "com.github.mercurievv.scalasemantic.analysis.RangeSelector[1-1]")
    )
    val entry = res("results").arr.head
    assertEquals(entry("found").bool, true)
    assertEquals(entry("uri").str, RangeSelectorUri)
    assert(entry("source").str.contains("package com.github.mercurievv.scalasemantic.analysis"))

  test("resolves a literal file-path target and renders the requested range"):
    val res = call(ujson.Obj("query" -> s"$RangeSelectorUri[1-1]"))
    val entry = res("results").arr.head
    assertEquals(entry("found").bool, true)
    assertEquals(entry("requestedRange").str, "1-1")
    assert(entry("source").str.contains("package com.github.mercurievv.scalasemantic.analysis"))

  test("defaults to detail=full when the caller does not pass a detail arg"):
    val res = call(ujson.Obj("query" -> s"$RangeSelectorUri[1-1]"))
    assert(res("results").arr.head("legend").str.nonEmpty)

  test("caller-supplied detail overrides the default without error"):
    val res = call(ujson.Obj("query" -> s"$RangeSelectorUri[1-1]", "detail" -> "terse"))
    assertEquals(res("results").arr.head("found").bool, true)

  test("renders multiple ranges of one target and multiple targets in one call"):
    val res = call(
      ujson.Obj("query" -> s"$RangeSelectorUri[1-1;2-2];$RangeSelectorPropertySuiteUri[1-1]")
    )
    val results = res("results").arr.toList
    assertEquals(results.size, 3)
    assertEquals(results.map(_("found").bool), List(true, true, true))
    assertEquals(
      results.map(_("uri").str),
      List(RangeSelectorUri, RangeSelectorUri, RangeSelectorPropertySuiteUri)
    )
    assertEquals(results.map(_("requestedRange").str), List("1-1", "2-2", "1-1"))
