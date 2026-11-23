package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import com.github.mercurievv.scalasemantic.wire.AnalysisCodecs.given
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** The analysis tools' `outputSchema` (types in `AnalysisToolResults`, models via the `wire`
  * module) against their real answers, from this suite's own tool instances (never `Mcp.handle`).
  * An absent optional is an absent key on the wire — never an explicit `null`.
  */
class AnalysisToolSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val Parts =
    SemSym.mainFile("ToolResultParts.scala")

  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))

  private def check(name: String, args: (String, J.V)*): Json =
    val schema = tool(name).outputSchema.getOrElse(fail(s"$name has no outputSchema"))
    val res = tool(name).run(J.obj(args*))
    assertEquals(SchemaConformance.violations(schema, res), Nil, J.write(res).take(300))
    res

  private def file(fields: (String, J.V)*): Json = J.obj(fields*)

  private def hasNull(v: Json): Boolean =
    v.fold(
      true,
      _ => false,
      _ => false,
      _ => false,
      a => a.exists(hasNull),
      o => o.values.exists(hasNull)
    )

  /** The answer, skipping the test when the tool needs a presentation compiler this server lacks;
    * any other failure (including a schema violation) still fails the test.
    */
  private def unlessNoPc(outcome: scala.util.Try[Json]): Json =
    outcome.fold(
      e =>
        if Option(e.getMessage).exists(_.contains("presentation compiler")) then
          assume(false, e.getMessage)
          Json.Null
        else fail(String.valueOf(e.getMessage), e),
      identity
    )

  test("structure conforms: default, and detailed with graph and a path filter"):
    val plain = check("structure", "limit" -> 5)
    assert(plain("symbols").arr.nonEmpty)
    val rich = check(
      "structure",
      "limit" -> 5,
      "detailed" -> true,
      "graph" -> true,
      "dimension" -> "call",
      "pathFilter" -> "*mcp*"
    )
    assert(rich("symbols")(0).obj.contains("perDimension"))

  test("value_flow conforms: a traced binding, and nothing to trace"):
    val flow = check(
      "value_flow",
      "symbol" -> "com/github/mercurievv/scalasemantic/mcp/BatchTool.runOne().(call)"
    )
    assert(flow.obj.contains("root"))
    assertEquals(check("value_flow")("found").bool, false)

  test("smart_code_duplications conforms, with and without source"):
    val args = List("minSize" -> J.num(40), "pathFilter" -> J.str("*mcp/src/main*"))
    check("smart_code_duplications", args*)
    check("smart_code_duplications", (args :+ ("showSource" -> J.bool(true)))*)

  test("source_ranges conforms: a slice, an unknown target and an empty range"):
    val res = check("source_ranges", "query" -> s"$Parts[1-5]")
    assertEquals(res("results")(0)("found").bool, true)
    val miss = check("source_ranges", "query" -> "no/such/File.scala[1-3]")
    assertEquals(miss("results")(0)("found").bool, false)
    check("source_ranges", "query" -> s"$Parts[90000-90001]")

  test("literal_tokens conforms, including a file that is missing (error present, no nulls)"):
    val res = check(
      "literal_tokens",
      "files" -> J.arr(file("uri" -> Parts), file("uri" -> "no/such/File.scala"))
    )
    assert(res("files")(0)("literals").arr.nonEmpty)
    assert(!res("files")(0).obj.contains("error"), "error is absent on success, not null")
    assert(res("files")(1)("error").strOpt.isDefined)
    assert(!hasNull(res), "an absent optional is an absent key")

  test("contract_clauses conforms: a file with contracts, a file with none, a missing file"):
    val src =
      "object A:\n  def f(x: Int): Int =\n    require(x > 0)\n    x\n  .ensuring(_ > 0)\n"
    val res = check(
      "contract_clauses",
      "files" -> J.arr(
        file("uri" -> "A.scala", "source" -> src),
        file("uri" -> Parts),
        file("uri" -> "no/such/File.scala")
      )
    )
    assert(!hasNull(res), "an absent optional is an absent key")

  test("resolve_references conforms, with a resolved range and a missing file"):
    val outcome = scala.util.Try(
      check(
        "resolve_references",
        "files" -> J.arr(
          file("uri" -> Parts, "ranges" -> J.arr(J.arr(8, 14))),
          file("uri" -> "no/such/File.scala", "ranges" -> J.arr(J.arr(0, 1)))
        )
      )
    )
    val res = unlessNoPc(outcome)
    assert(res("files")(0)("references").arr.nonEmpty)
    assert(!hasNull(res), "an absent optional is an absent key")

  test("hole_context conforms (needs the presentation compiler; skipped without a classpath)"):
    val src = "object A:\n  def f(x: Int): String = ???\n"
    val outcome = scala.util.Try(
      check("hole_context", "uri" -> "A.scala", "line" -> 1, "character" -> 27, "source" -> src)
    )
    val res = unlessNoPc(outcome)
    assert(!hasNull(res), "an absent optional is an absent key")

  test("the models, encoded by the wire module, conform to the schemas the tools declare"):
    val refs = TypedTool.encode(
      ReferenceResult(
        List(
          FileReferences(
            "a.scala",
            List(Reference(1, 2, "n", "term", "use", false, None, None, List("m"))),
            0,
            None
          ),
          FileReferences("b.scala", Nil, 0, Some("no source"))
        )
      )
    )
    val hole = TypedTool.encode(
      HoleContextResult(
        found = false,
        why = Some("no hole"),
        hole = None,
        expected = Some(HoleExpected(None, "low", "why")),
        bindings = Nil,
        producers = Nil,
        givens = Nil,
        types = List(HoleType("s", "project", "sig", None)),
        omitted = HoleOmitted(0, 0, 0, 0)
      )
    )
    val lits = TypedTool.encode(
      LiteralResult(
        List(
          FileLiterals(
            "x",
            List(
              Literal(1, 2, "int", "1", None),
              Literal(3, 4, "int", "2", Some(Enclosing("val", "v", 3)))
            ),
            0,
            None
          )
        )
      )
    )
    List("resolve_references" -> refs, "hole_context" -> hole, "literal_tokens" -> lits).foreach:
      (name, wire) =>
        val schema = tool(name).outputSchema.getOrElse(fail(s"$name has no schema"))
        assertEquals(SchemaConformance.violations(schema, wire), Nil, J.write(wire).take(300))
        assert(!hasNull(wire), name)

  test("schemas stay small"):
    List(
      "structure",
      "value_flow",
      "smart_code_duplications",
      "source_ranges",
      "literal_tokens",
      "contract_clauses",
      "resolve_references",
      "hole_context"
    ).foreach: n =>
      val chars = J.write(tool(n).outputSchema.getOrElse(fail(n))).length
      println(s"$n outputSchema: $chars chars")
      assert(chars < 4000, s"$n schema is $chars chars")
