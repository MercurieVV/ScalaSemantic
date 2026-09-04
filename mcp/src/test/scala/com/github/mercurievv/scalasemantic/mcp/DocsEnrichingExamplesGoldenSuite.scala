package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import upickle.default.write

import java.nio.file.Files
import java.nio.file.Paths

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
  private val docExamplesClasses = Paths.get("out/docExamples/compile.dest/classes")
  private val az = Analyzer(SemanticIndex.fromRoots(Seq(docExamplesClasses)))
  private val tools = McpTools.all(az, root)

  private def toolByName(name: String): Tool =
    tools.find(_.name == name).getOrElse(fail(s"unknown tool: $name"))

  private val goldenDir = Paths.get("mcp/src/test/resources/docs-golden")

  /** Placeholder swapped into the JSON golden in place of an embedded `"source"` field — the real
    * text lives in a sibling `$exampleName.scala` file instead, so it stays diffable/editable as
    * plain Scala rather than an escaped JSON string.
    */
  private val sourcePlaceholder = "<<see companion .scala golden file>>"

  private def assertGolden(exampleName: String, tool: String, args: ujson.Value): Unit =
    val result = toolByName(tool).run(args)
    val sourceOpt = result.objOpt.flatMap(_.get("source")).collect { case ujson.Str(s) => s }
    val jsonResult = sourceOpt match
      case Some(_) =>
        val entries = result.obj.toSeq.map {
          case (k, _) if k == "source" => k -> (ujson.Str(sourcePlaceholder): ujson.Value)
          case kv                      => kv
        }
        ujson.Obj(entries.head, entries.tail*)
      case None => result
    val actual =
      write(ujson.Obj("tool" -> tool, "args" -> args, "result" -> jsonResult), indent = 2)
    val goldenPath = goldenDir.resolve(s"$exampleName.json")

    sourceOpt.foreach { src =>
      val scalaGoldenPath = goldenDir.resolve(s"$exampleName.scala")
      if Files.exists(scalaGoldenPath) then
        val expectedSrc = Files.readString(scalaGoldenPath)
        assertEquals(
          src,
          expectedSrc,
          s"doc example '$exampleName' source drifted from its golden .scala file"
        )
      else
        val _ = Files.writeString(scalaGoldenPath, src)
    }

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

  test("annotated_source(Enrich.scala, sentinel=on)"):
    assertGolden(
      "annotated_source_enrich_sentinel",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "compilable",
        "annotationsOnly" -> false,
        "sentinel" -> true
      )
    )

  test("annotated_source(Enrich.scala, symbols=on)"):
    assertGolden(
      "annotated_source_enrich_symbols",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "compilable",
        "annotationsOnly" -> false,
        "symbols" -> true
      )
    )

  test("annotated_source(Enrich.scala, format=diff, symbols=on)"):
    assertGolden(
      "annotated_source_enrich_diff",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "diff",
        "annotationsOnly" -> false,
        "symbols" -> true
      )
    )

  test("annotated_source(Enrich.scala, format=diff, docs=strip)"):
    assertGolden(
      "annotated_source_enrich_docs_diff",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "diff",
        "annotationsOnly" -> false,
        "docs" -> "strip"
      )
    )

  test("annotated_source(Enrich.scala, format=diff, all-on)"):
    assertGolden(
      "annotated_source_enrich_all_diff",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "diff",
        "annotationsOnly" -> false,
        "symbols" -> true,
        "detail" -> "full",
        "docs" -> "keep"
      )
    )

  test("annotated_source(Enrich.scala, docs=strip)"):
    assertGolden(
      "annotated_source_enrich_docs",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "compilable",
        "annotationsOnly" -> false,
        "docs" -> "strip"
      )
    )

  test("annotated_source(Enrich.scala, detail=full)"):
    assertGolden(
      "annotated_source_enrich_full",
      "annotated_source",
      ujson.Obj(
        "uri" -> EnrichPath,
        "format" -> "compilable",
        "annotationsOnly" -> false,
        "detail" -> "full"
      )
    )

  test("method_signature(render)"):
    assertGolden("method_signature_render", "method_signature", ujson.Obj("symbol" -> Render))

  test("symbol_source(render) returns just that def, not Show/the givens/anything else"):
    val args = ujson.Obj("symbol" -> Render, "format" -> "plain")
    val src = toolByName("symbol_source").run(args)("source").str
    assert(src.contains("def render[A: Show](a: A): String = Show[A].show(a)"), src)
    assert(!src.contains("trait Show"), src)
    assert(!src.contains("given intShow"), src)
    assert(!src.contains("extension (n: Int)"), src)
    assertGolden("symbol_source_render", "symbol_source", args)

  test("symbol_source accepts a dotted FQN form of the same symbol"):
    val fqn = "com.github.mercurievv.scalasemantic.docexamples.render()"
    val args = ujson.Obj("symbol" -> fqn, "format" -> "plain")
    val src = toolByName("symbol_source").run(args)("source").str
    assert(src.contains("def render[A: Show](a: A): String = Show[A].show(a)"), src)
    assertGolden("symbol_source_render_fqn", "symbol_source", args)

  test("source_around_position(Enrich.scala render body) resolves the enclosing def, not the file"):
    // column 40 lands inside `Show[A].show(a)`, the RHS of `render`'s single-line def — a reference,
    // not a definition, proving the tool anchors to the ENCLOSING def rather than jumping to the
    // referenced symbol's own (unrelated) definition.
    val args =
      ujson.Obj("file" -> EnrichPath, "line" -> 27, "column" -> 40, "format" -> "plain")
    val src = toolByName("source_around_position").run(args)("source").str
    assert(src.contains("def render[A: Show](a: A): String = Show[A].show(a)"), src)
    assert(!src.contains("trait Show"), src)
    assert(!src.contains("given intShow"), src)
    assertGolden("source_around_position_render", "source_around_position", args)

  test("source_around_position falls back to a fixed window when nothing encloses the position"):
    val args = ujson.Obj("file" -> EnrichPath, "line" -> 0, "column" -> 0, "format" -> "plain")
    val r = toolByName("source_around_position").run(args)
    assert(r("legend").str.toLowerCase.contains("fallback"), r("legend").str)

  test("document_outline(Enrich.scala)"):
    assertGolden("document_outline_enrich", "document_outline", ujson.Obj("uri" -> EnrichPath))

  test("type_at_position(Enrich.scala:47:4)"):
    assertGolden(
      "type_at_position_enrich",
      "type_at_position",
      ujson.Obj("uri" -> EnrichPath, "line" -> 47, "character" -> 4)
    )

  test("type_at_position(Enrich.scala:14:6)"):
    assertGolden(
      "type_at_position_enrich_apply",
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

  test("trace_implicit_chain(Show[List[Int]])"):
    assertGolden(
      "trace_implicit_chain_show_list_int",
      "trace_implicit_chain",
      ujson.Obj("type" -> ShowType, "appliedType" -> "Show[List[Int]]")
    )
