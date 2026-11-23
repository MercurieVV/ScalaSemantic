package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** The edit-plan tools' `outputSchema` (types in `PlanToolResults`) against their real answers,
  * from this suite's own tool instances (never `Mcp.handle`).
  */
class PlanToolSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val Parts =
    "wire/src/main/scala/com/github/mercurievv/scalasemantic/wire/api/ToolResultParts.scala"
  private val Sym = "com/github/mercurievv/scalasemantic/wire/api/NotFoundResult#"

  private def check(name: String, args: (String, J.V)*): Json =
    val t = tools.find(_.name == name).getOrElse(fail(s"no $name"))
    val schema = t.outputSchema.getOrElse(fail(s"$name has no outputSchema"))
    val res = t.run(J.obj(args*))
    assertEquals(SchemaConformance.violations(schema, res), Nil, J.write(res).take(300))
    res

  test("rename_plan conforms, also for a symbol with no occurrences"):
    val res = check("rename_plan", "symbol" -> Sym, "newName" -> "NotFoundOut")
    assert(res("editCount").num > 0)
    check("rename_plan", "symbol" -> "no/such/Symbol#", "newName" -> "Other")

  test("batch_rename_plan conforms: clean batch and one with overlapping edits"):
    def req(s: String, n: String) = J.obj("symbol" -> s, "newName" -> n)
    val clean = check("batch_rename_plan", "renames" -> J.arr(req(Sym, "A")))
    assertEquals(clean("conflictCount").num.toInt, 0)
    val clash = check("batch_rename_plan", "renames" -> J.arr(req(Sym, "A"), req(Sym, "B")))
    assert(clash("conflictCount").num > 0, "same symbol renamed twice must conflict")

  test("move_plan conforms, with and without references"):
    val res = check("move_plan", "symbol" -> Sym, "newOwner" -> "com/github/mercurievv/other/")
    assert(res("referenceCount").num > 0)
    check("move_plan", "symbol" -> "no/such/Symbol#", "newOwner" -> "com/github/mercurievv/other/")

  test("extract_method_plan conforms: a range and an unknown file"):
    check(
      "extract_method_plan",
      "uri" -> Parts,
      "startLine" -> 44,
      "startCharacter" -> 4,
      "endLine" -> 45,
      "endCharacter" -> 40
    )
    val missing = check(
      "extract_method_plan",
      "uri" -> "no/such/File.scala",
      "startLine" -> 0,
      "startCharacter" -> 0,
      "endLine" -> 1,
      "endCharacter" -> 0
    )
    assertEquals(missing("found").bool, false)

  test("plan schemas stay small"):
    List("rename_plan", "batch_rename_plan", "move_plan", "extract_method_plan").foreach: n =>
      val chars =
        J.write(tools.find(_.name == n).flatMap(_.outputSchema).getOrElse(fail(n))).length
      println(s"$n outputSchema: $chars chars")
      assert(chars < 2500, s"$n schema is $chars chars")
