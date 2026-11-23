package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Path
import java.nio.file.Paths

/** The analysis tools' `outputSchema` (types in `AnalysisToolResults`) against their real answers,
  * from this suite's own tool instances (never `Mcp.handle`). Tools that write an analysis-module
  * model write an absent `Option` as `null`; those schemas must accept it.
  */
class AnalysisToolSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val Parts =
    "mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/ToolResultParts.scala"

  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))

  private def check(name: String, args: (String, ujson.Value)*): ujson.Value =
    val schema = tool(name).outputSchema.getOrElse(fail(s"$name has no outputSchema"))
    val res = tool(name).run(ujson.Obj.from(args))
    assertEquals(SchemaConformance.violations(schema, res), Nil, ujson.write(res).take(300))
    res

  private def file(fields: (String, ujson.Value)*): ujson.Value = ujson.Obj.from(fields)

  /** The answer, skipping the test when the tool needs a presentation compiler this server lacks;
    * any other failure (including a schema violation) still fails the test.
    */
  private def unlessNoPc(outcome: scala.util.Try[ujson.Value]): ujson.Value =
    outcome.fold(
      e =>
        if Option(e.getMessage).exists(_.contains("presentation compiler")) then
          assume(false, e.getMessage)
          ujson.Null
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
    val args = List("minSize" -> ujson.Num(40), "pathFilter" -> ujson.Str("*mcp/src/main*"))
    check("smart_code_duplications", args*)
    check("smart_code_duplications", (args :+ ("showSource" -> ujson.Bool(true)))*)

  test("source_ranges conforms: a slice, an unknown target and an empty range"):
    val res = check("source_ranges", "query" -> s"$Parts[1-5]")
    assertEquals(res("results")(0)("found").bool, true)
    val miss = check("source_ranges", "query" -> "no/such/File.scala[1-3]")
    assertEquals(miss("results")(0)("found").bool, false)
    check("source_ranges", "query" -> s"$Parts[90000-90001]")

  test("literal_tokens conforms, including a file that is missing (error is non-null)"):
    val res = check(
      "literal_tokens",
      "files" -> ujson.Arr(file("uri" -> Parts), file("uri" -> "no/such/File.scala"))
    )
    assert(res("files")(0)("literals").arr.nonEmpty)
    assert(res("files")(1)("error").strOpt.isDefined)

  test("contract_clauses conforms: a file with contracts, a file with none, a missing file"):
    val src =
      "object A:\n  def f(x: Int): Int =\n    require(x > 0)\n    x\n  .ensuring(_ > 0)\n"
    check(
      "contract_clauses",
      "files" -> ujson.Arr(
        file("uri" -> "A.scala", "source" -> src),
        file("uri" -> Parts),
        file("uri" -> "no/such/File.scala")
      )
    )

  test("resolve_references conforms, with a resolved range and a missing file"):
    val outcome = scala.util.Try(
      check(
        "resolve_references",
        "files" -> ujson.Arr(
          file("uri" -> Parts, "ranges" -> ujson.Arr(ujson.Arr(8, 14))),
          file("uri" -> "no/such/File.scala", "ranges" -> ujson.Arr(ujson.Arr(0, 1)))
        )
      )
    )
    val res = unlessNoPc(outcome)
    assert(res("files")(0)("references").arr.nonEmpty)

  test("hole_context conforms (needs the presentation compiler; skipped without a classpath)"):
    val src = "object A:\n  def f(x: Int): String = ???\n"
    val outcome = scala.util.Try(
      check("hole_context", "uri" -> "A.scala", "line" -> 1, "character" -> 27, "source" -> src)
    )
    val _ = unlessNoPc(outcome)

  test("nullable-option schemas accept null where the wire writes it"):
    val schema = tool("literal_tokens").outputSchema.getOrElse(fail("no schema"))
    val withNull = ujson.Obj(
      "files" -> ujson.Arr(
        ujson.Obj(
          "uri" -> "x",
          "literals" -> ujson.Arr(
            ujson.Obj(
              "line" -> 1,
              "character" -> 2,
              "kind" -> "int",
              "value" -> "1",
              "enclosing" -> ujson.Null
            )
          ),
          "omitted" -> 0,
          "error" -> ujson.Null
        )
      )
    )
    assertEquals(SchemaConformance.violations(schema, withNull), Nil)

  test("resolve_references and hole_context schemas accept the models as the tools write them"):
    import com.github.mercurievv.scalasemantic.model.*
    val refs = upickle.default.writeJs(
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
    val hole = upickle.default.writeJs(
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
    List("resolve_references" -> refs, "hole_context" -> hole).foreach: (name, wire) =>
      val schema = tool(name).outputSchema.getOrElse(fail(s"$name has no schema"))
      assertEquals(SchemaConformance.violations(schema, wire), Nil, ujson.write(wire).take(300))

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
      val chars = ujson.write(tool(n).outputSchema.getOrElse(fail(n))).length
      println(s"$n outputSchema: $chars chars")
      assert(chars < 4000, s"$n schema is $chars chars")
