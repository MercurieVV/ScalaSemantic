package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import com.github.mercurievv.scalasemantic.wire.api.*
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** The position/source tools build their JSON with shared helpers; their `outputSchema` comes from
  * the types in `SourceToolResults`. These tests run the real tools (this suite's own instances,
  * never `Mcp.handle`) and check every answer against the declared schema.
  */
class SourceToolSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val Parts =
    SemSym.mainFile("ToolResultParts.scala")
  private val NotFoundSym = SemSym.typeOf[NotFoundResult]

  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))

  private def check(name: String, args: (String, J.V)*): Json =
    val t = tool(name)
    val schema = t.outputSchema.getOrElse(fail(s"$name has no outputSchema"))
    val res = t.run(J.obj(args*))
    assertEquals(SchemaConformance.violations(schema, res), Nil, J.write(res).take(300))
    res

  test("every source/position tool declares an outputSchema"):
    List(
      "type_at_position",
      "search_text",
      "document_outline",
      "annotated_source",
      "symbol_source",
      "source_around_position"
    ).foreach(n => assert(tool(n).outputSchema.isDefined, n))

  test("type_at_position: a hit and a miss conform"):
    val hit =
      check("type_at_position", "uri" -> Parts, "line" -> 8, "character" -> 17)
    assert(hit.obj.contains("symbol") || hit.obj.get("found").contains(J.bool(false)))
    val miss = check("type_at_position", "uri" -> Parts, "line" -> 9000, "character" -> 0)
    assertEquals(miss("found").bool, false)

  test("search_text conforms, with hits and with none"):
    val some = check("search_text", "query" -> "NotFoundResult", "limit" -> 5)
    assert(some("hits").arr.nonEmpty)
    val none = check("search_text", "query" -> ("no_such_text" + "_anywhere_zz9"))
    assertEquals(none("hits").arr.size, 0)

  test("document_outline conforms: whole file, narrowed, and unknown uri"):
    val whole = check("document_outline", "uri" -> Parts)
    assert(whole("outline").arr.nonEmpty)
    check("document_outline", "uri" -> Parts, "query" -> "Lookup", "maxDepth" -> 1)
    assertEquals(check("document_outline", "uri" -> "no/such/File.scala")("found").bool, false)

  test("annotated_source conforms in every format, and for an unknown uri"):
    List("annotated", "compilable", "plain", "diff").foreach(f =>
      check("annotated_source", "uri" -> Parts, "format" -> f)
    )
    check("annotated_source", "uri" -> Parts, "annotationsOnly" -> true, "symbols" -> true)
    assertEquals(check("annotated_source", "uri" -> "no/such/File.scala")("found").bool, false)

  test("symbol_source conforms: found, FQN with note-free answer, and not found"):
    val found = check("symbol_source", "symbol" -> NotFoundSym)
    assert(found("source").str.nonEmpty)
    check("symbol_source", "symbol" -> "no/such/Symbol#")

  test("source_around_position conforms: inside a definition and for an unknown file"):
    check("source_around_position", "file" -> Parts, "line" -> 9, "column" -> 5)
    check("source_around_position", "file" -> "no/such/File.scala", "line" -> 1, "column" -> 1)

  test("document_outline's schema is recursive through a $ref into $defs"):
    val s = J.write(tool("document_outline").outputSchema.getOrElse(fail("no schema")))
    assert(s.contains("$defs") && s.contains("$ref"), s.take(400))

  test("schemas stay small (they ship in the on-demand output_schema resource)"):
    List("type_at_position", "search_text", "document_outline", "annotated_source").foreach: n =>
      val chars = J.write(tool(n).outputSchema.getOrElse(fail(n))).length
      println(s"$n outputSchema: $chars chars")
      assert(chars < 3500, s"$n schema is $chars chars")
