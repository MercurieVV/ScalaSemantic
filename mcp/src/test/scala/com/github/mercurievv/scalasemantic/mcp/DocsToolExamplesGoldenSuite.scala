package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import upickle.default.write

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

  test("find_symbol('transform')"):
    assertGolden("find_symbol_transform", "find_symbol", ujson.Obj("query" -> "transform"))

  test("class_hierarchy(Processor)"):
    assertGolden("class_hierarchy_processor", "class_hierarchy", ujson.Obj("symbol" -> Processor))

  test("find_overloads(format)"):
    assertGolden("find_overloads_format", "find_overloads", ujson.Obj("symbol" -> Format))

  test("find_usages(transform)"):
    assertGolden("find_usages_transform", "find_usages", ujson.Obj("symbol" -> Transform))

  test("members(UpperProcessor)"):
    assertGolden("members_upper_processor", "members", ujson.Obj("symbol" -> UpperProcessor))

  test("call_path(pipeline -> process)"):
    assertGolden(
      "call_path_pipeline_process",
      "call_path",
      ujson.Obj("from" -> Pipeline, "to" -> ProcessorProcess, "detailed" -> true)
    )

  test("method_call_hierarchy(pipeline, callees)"):
    assertGolden(
      "method_call_hierarchy_pipeline",
      "method_call_hierarchy",
      ujson.Obj("symbol" -> Pipeline, "direction" -> "callees")
    )

  test("value_flow(Navigate.scala:19:13)"):
    assertGolden(
      "value_flow_navigate",
      "value_flow",
      ujson.Obj("file" -> NavigateFile, "line" -> 19, "column" -> 13)
    )

  test("rename_plan(transform -> apply)"):
    assertGolden(
      "rename_plan_transform",
      "rename_plan",
      ujson.Obj("symbol" -> Transform, "newName" -> "apply")
    )

  test("move_plan(calculateTotal -> com/example/math/)"):
    assertGolden(
      "move_plan_calculate_total",
      "move_plan",
      ujson.Obj("symbol" -> CalculateTotal, "newOwner" -> "com/example/math/")
    )

  test("extract_method_plan(Refactor.scala 5:20-8:9)"):
    assertGolden(
      "extract_method_plan_refactor",
      "extract_method_plan",
      ujson.Obj(
        "uri" -> RefactorFile,
        "startLine" -> 5,
        "startCharacter" -> 20,
        "endLine" -> 8,
        "endCharacter" -> 9
      )
    )

  test("structure()"):
    assertGolden("structure_all", "structure", ujson.Obj())

  test("smart_code_duplications(minSize=15)"):
    assertGolden(
      "smart_code_duplications_min15",
      "smart_code_duplications",
      ujson.Obj("minSize" -> 15)
    )
