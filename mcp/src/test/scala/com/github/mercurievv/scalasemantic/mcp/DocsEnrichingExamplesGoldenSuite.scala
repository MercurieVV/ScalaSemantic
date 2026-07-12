package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import upickle.default.write

import java.nio.file.{Files, Paths}

/** LOCKED — source of truth for the index-only tool calls shown in `docs/usage/tool-examples.md`'s
  * "Enriching tools" section, against the `Enrich.scala` fixture. See
  * [[DocsToolExamplesGoldenSuite]] for the golden-file mechanics and update discipline.
  *
  * Scope: only the plain (disk-index) calls are pinned here. The same section's presentation-
  * compiler buffer-diff demo (`method_signature` against DB / PC-same / PC-modified) boots a real
  * Metals-style presentation compiler per case — slow, and illustrative of a live-edit workflow
  * rather than a single stable JSON contract — so it is left as a doc-time-only demo, not golden-
  * locked here.
  */
class DocsEnrichingExamplesGoldenSuite extends munit.FunSuite:

  private val root = Paths.get(".").toAbsolutePath.nn
  private val tools = McpTools.all(Analyzer(SemanticIndex.fromProject(".")), root)

  private def toolByName(name: String): Tool =
    tools.find(_.name == name).getOrElse(fail(s"unknown tool: $name"))

  private val goldenDir = Paths.get("mcp/src/test/resources/docs-golden")

  private def assertGolden(exampleName: String, tool: String, args: ujson.Value): Unit =
    val result = toolByName(tool).run(args)
    val actual = write(ujson.Obj("tool" -> tool, "args" -> args, "result" -> result), indent = 2)
    val goldenPath = goldenDir.resolve(s"$exampleName.json")
    if Files.exists(goldenPath) then
      val expected = Files.readString(goldenPath)
      assertEquals(actual, expected, s"doc example '$exampleName' drifted from its golden file")
    else
      val _ = Files.createDirectories(goldenPath.getParent)
      val _ = Files.writeString(goldenPath, actual)

  private val EnrichPath =
    "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
  private val Render = "com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."
  private val ShowType = "com/github/mercurievv/scalasemantic/docexamples/Show#"

  test("annotated_source(Enrich.scala)"):
    assertGolden(
      "annotated_source_enrich",
      "annotated_source",
      ujson.Obj("uri" -> EnrichPath, "format" -> "compilable", "annotationsOnly" -> false)
    )

  test("method_signature(render)"):
    assertGolden("method_signature_render", "method_signature", ujson.Obj("symbol" -> Render))

  test("document_outline(Enrich.scala)"):
    assertGolden("document_outline_enrich", "document_outline", ujson.Obj("uri" -> EnrichPath))

  test("type_at_position(Enrich.scala:14:6)"):
    assertGolden(
      "type_at_position_enrich",
      "type_at_position",
      ujson.Obj("uri" -> EnrichPath, "line" -> 14, "character" -> 6)
    )

  test("resolve_implicits(Show)"):
    assertGolden("resolve_implicits_show", "resolve_implicits", ujson.Obj("type" -> ShowType))

  test("trace_implicit_chain(Show)"):
    assertGolden(
      "trace_implicit_chain_show",
      "trace_implicit_chain",
      ujson.Obj("type" -> ShowType)
    )
