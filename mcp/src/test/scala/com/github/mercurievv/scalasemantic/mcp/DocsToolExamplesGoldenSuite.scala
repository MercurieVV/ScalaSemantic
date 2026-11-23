package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import io.circe.Json

import java.nio.file.Files
import java.nio.file.Paths

/** LOCKED — this is the source of truth for every tool call shown in `docs/usage/tool-examples.md`.
  * The docs page renders these same tool/args pairs at build time via `ToolRunner`, which indexes
  * ONLY `docExamples`' compiled classes (see `docs.forkArgs`'s `scalasemantic.docs.indexDir` in
  * build.mill) — not the whole repo. This suite mirrors that exact index construction so its golden
  * output matches the doc page byte for byte, and so whole-repo-scan tools (`structure`,
  * `smart_code_duplications`) stay stable instead of drifting whenever an unrelated module
  * compiles.
  *
  * Changing a golden file must go hand in hand with updating the matching example in
  * `docs/usage/tool-examples.md` — do not "fix" a failing test here by only regenerating the golden
  * file without also checking the doc page still reads correctly.
  *
  *   - **First run** (golden file absent): the file is written automatically and the test passes.
  *     Review the written file, then commit it.
  *   - **Regenerate**: delete the golden file and re-run the test.
  */
class DocsToolExamplesGoldenSuite extends munit.FunSuite:

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val root = Paths.get(".").toAbsolutePath.nn
  private val docExamplesClasses = Paths.get("out/docExamples/compile.dest/classes")
  private val tools = McpTools.all(Analyzer(SemanticIndex.fromRoots(Seq(docExamplesClasses))), root)

  private def toolByName(name: String): Tool =
    tools.find(_.name == name).getOrElse(fail(s"unknown tool: $name"))

  private val goldenDir = Paths.get("mcp/src/test/resources/docs-golden")

  private def assertGolden(exampleName: String, tool: String, args: Json): Unit =
    val result = toolByName(tool).run(args)
    val actual = J.write(J.obj("tool" -> tool, "args" -> args, "result" -> result), indent = 2)
    val goldenPath = goldenDir.resolve(s"$exampleName.json")
    if Files.exists(goldenPath) then
      val expected = Files.readString(goldenPath)
      assertEquals(actual, expected, s"doc example '$exampleName' drifted from its golden file")
    else
      val _ = Files.createDirectories(goldenPath.getParent)
      val _ = Files.writeString(goldenPath, actual)

  private val Processor = "com/github/mercurievv/scalasemantic/docexamples/Processor#"
  private val UpperProcessor = "com/github/mercurievv/scalasemantic/docexamples/UpperProcessor#"
  private val Format =
    "com/github/mercurievv/scalasemantic/docexamples/Overloading$package.format()."
  private val Transform =
    "com/github/mercurievv/scalasemantic/docexamples/Navigate$package.transform()."
  private val CalculateTotal =
    "com/github/mercurievv/scalasemantic/docexamples/Refactor$package.calculateTotal()."
  private val Pipeline =
    "com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline()."
  private val ProcessorProcess =
    "com/github/mercurievv/scalasemantic/docexamples/Processor#process()."
  private val NavigateFile =
    "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Navigate.scala"
  private val RefactorFile =
    "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Refactor.scala"
  private val EnrichFile =
    "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"

  test("literal_tokens(a note table)"):
    assertGolden(
      "literal_tokens_table",
      "literal_tokens",
      J.obj(
        "files" -> J.arr(
          J.obj(
            "uri" -> "Notes.scala",
            "source" -> "package demo\nobject Notes:\n  val midi = Map(\"C4\" -> 60, \"D4\" -> 62)\n  def label(n: Int) = s\"note $n\"\n"
          )
        )
      )
    )

  test("contract_clauses(a pure kernel)"):
    assertGolden(
      "contract_clauses_kernel",
      "contract_clauses",
      J.obj(
        "files" -> J.arr(
          J.obj(
            "uri" -> "PureKernels.scala",
            "source" -> "package demo\nimport stainless.lang.*\nobject PureKernels:\n  def framesToNanos(frames: Int, sampleRate: Int): Long = {\n    require(frames >= 0 && sampleRate > 0)\n    frames.toLong * 1000000000L / sampleRate\n  } ensuring (r => r >= 0)\n"
          )
        )
      )
    )

  test("find_symbol('transform')"):
    assertGolden("find_symbol_transform", "find_symbol", J.obj("query" -> "transform"))

  test("class_hierarchy(Processor)"):
    assertGolden("class_hierarchy_processor", "class_hierarchy", J.obj("symbol" -> Processor))

  test("find_overloads(format)"):
    assertGolden("find_overloads_format", "find_overloads", J.obj("symbol" -> Format))

  test("find_usages(transform)"):
    assertGolden("find_usages_transform", "find_usages", J.obj("symbol" -> Transform))

  test("find_usages(transform, contextLines=1)"):
    assertGolden(
      "find_usages_transform_context",
      "find_usages",
      J.obj("symbol" -> Transform, "contextLines" -> 1)
    )

  test("search_text(context bound)"):
    assertGolden(
      "search_text_context_bound",
      "search_text",
      J.obj(
        "query" -> "context bound",
        "pathFilter" -> "*docexamples/Enrich.scala*"
      )
    )

  test("members(UpperProcessor)"):
    assertGolden("members_upper_processor", "members", J.obj("symbol" -> UpperProcessor))

  test("call_path(pipeline -> process)"):
    assertGolden(
      "call_path_pipeline_process",
      "call_path",
      J.obj("from" -> Pipeline, "to" -> ProcessorProcess, "detailed" -> true)
    )

  test("method_call_hierarchy(pipeline, callees)"):
    assertGolden(
      "method_call_hierarchy_pipeline",
      "method_call_hierarchy",
      J.obj("symbol" -> Pipeline, "direction" -> "callees")
    )

  test("value_flow(Navigate.scala:19:13)"):
    assertGolden(
      "value_flow_navigate",
      "value_flow",
      J.obj("file" -> NavigateFile, "line" -> 19, "column" -> 13)
    )

  test("rename_plan(transform -> apply)"):
    assertGolden(
      "rename_plan_transform",
      "rename_plan",
      J.obj("symbol" -> Transform, "newName" -> "apply")
    )

  test("batch_rename_plan(transform, calculateTotal)"):
    assertGolden(
      "batch_rename_plan_transform_calculate_total",
      "batch_rename_plan",
      J.obj(
        "renames" -> J.arr(
          J.obj("symbol" -> Transform, "newName" -> "apply"),
          J.obj("symbol" -> CalculateTotal, "newName" -> "total")
        )
      )
    )

  test("move_plan(calculateTotal -> com/example/math/)"):
    assertGolden(
      "move_plan_calculate_total",
      "move_plan",
      J.obj("symbol" -> CalculateTotal, "newOwner" -> "com/example/math/")
    )

  test("extract_method_plan(Refactor.scala 5:20-8:9)"):
    assertGolden(
      "extract_method_plan_refactor",
      "extract_method_plan",
      J.obj(
        "uri" -> RefactorFile,
        "startLine" -> 5,
        "startCharacter" -> 20,
        "endLine" -> 8,
        "endCharacter" -> 9
      )
    )

  test("structure()"):
    assertGolden("structure_all", "structure", J.obj())

  test("structure(graph=true)"):
    assertGolden("structure_graph", "structure", J.obj("graph" -> true))

  test("smart_code_duplications(minSize=15)"):
    assertGolden(
      "smart_code_duplications_min15",
      "smart_code_duplications",
      J.obj("minSize" -> 15)
    )

  test("smart_code_duplications(minSize=15, showSource=true)"):
    assertGolden(
      "smart_code_duplications_min15_show_source",
      "smart_code_duplications",
      J.obj("minSize" -> 15, "showSource" -> true)
    )

  test("source_ranges(Enrich.scala[28-28;49-49])"):
    assertGolden(
      "source_ranges_enrich_multi",
      "source_ranges",
      J.obj("query" -> s"$EnrichFile[28-28;49-49]")
    )

  test("source_ranges(Enrich.scala[43-49])"):
    assertGolden(
      "source_ranges_enrich_wide",
      "source_ranges",
      J.obj("query" -> s"$EnrichFile[43-49]")
    )
