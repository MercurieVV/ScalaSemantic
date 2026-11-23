package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Path
import java.nio.file.Paths

/** Output schemas are served on demand (resources + `output_schema` tool), not in `tools/list`.
  * `outputSchema` in the catalog and `structuredContent` in results are opt-in.
  */
class OutputSchemaResourcesSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val typed = List(
    "find_symbol",
    "find_usages",
    "method_signature",
    "class_hierarchy",
    "find_overloads",
    "members",
    "resolve_implicits",
    "call_path",
    "method_call_hierarchy",
    "trace_implicit_chain",
    "type_at_position",
    "search_text",
    "document_outline",
    "annotated_source",
    "symbol_source",
    "source_around_position",
    "rename_plan",
    "batch_rename_plan",
    "move_plan",
    "extract_method_plan",
    "structure",
    "value_flow",
    "smart_code_duplications",
    "source_ranges",
    "literal_tokens",
    "contract_clauses",
    "resolve_references",
    "hole_context"
  )
  private val Loc = "com/github/mercurievv/scalasemantic/mcp/McpToolsSupport.loc()."

  private def rpc(method: String, params: ujson.Value, structured: Boolean = false): ujson.Value =
    Mcp
      .handle(
        ujson.Obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> method, "params" -> params),
        tools,
        (_, _) => (),
        structured
      )
      .getOrElse(fail(s"no response to $method"))

  private def schemaOf(name: String): ujson.Value =
    tools.find(_.name == name).flatMap(_.outputSchema).getOrElse(fail(s"$name has no schema"))

  test("default: tools/list carries no outputSchema, tools/call no structuredContent"):
    val listed = rpc("tools/list", ujson.Obj())("result")("tools").arr.toList
    assert(listed.forall(!_.obj.contains("outputSchema")))
    val res = rpc(
      "tools/call",
      ujson.Obj("name" -> "find_symbol", "arguments" -> ujson.Obj("query" -> "Analyzer"))
    )("result")
    assert(!res.obj.contains("structuredContent"))
    assert(res("content")(0)("text").str.nonEmpty)

  test(
    "opt-in: tools/list advertises outputSchema for typed tools and calls add structuredContent"
  ):
    val listed = rpc("tools/list", ujson.Obj(), structured = true)("result")("tools").arr.toList
    assertEquals(
      listed.filter(_.obj.contains("outputSchema")).map(_("name").str).toSet,
      typed.toSet
    )
    val res = rpc(
      "tools/call",
      ujson.Obj("name" -> "find_symbol", "arguments" -> ujson.Obj("query" -> "Analyzer")),
      structured = true
    )("result")
    assertEquals(res("structuredContent"), ujson.read(res("content")(0)("text").str))

  test("initialize advertises the resources capability"):
    val caps = rpc("initialize", ujson.Obj())("result")("capabilities")
    assert(caps.obj.contains("tools") && caps.obj.contains("resources"))

  test("resources/templates/list exposes the per-tool template; resources/list the `all` bundle"):
    val template = rpc("resources/templates/list", ujson.Obj())("result")("resourceTemplates")(0)
    assertEquals(template("uriTemplate").str, "scalasemantic://output-schema/{tool}")
    val resource = rpc("resources/list", ujson.Obj())("result")("resources")(0)
    assertEquals(resource("uri").str, OutputSchemaResources.AllUri)

  test("resources/read returns one tool's schema"):
    val res =
      rpc("resources/read", ujson.Obj("uri" -> (OutputSchemaResources.UriPrefix + "find_usages")))
    val content = res("result")("contents")(0)
    assertEquals(content("mimeType").str, "application/json")
    assertEquals(ujson.read(content("text").str), schemaOf("find_usages"))

  test("resources/read `all` returns every typed tool's schema keyed by name"):
    val res = rpc("resources/read", ujson.Obj("uri" -> OutputSchemaResources.AllUri))
    val all = ujson.read(res("result")("contents")(0)("text").str)
    assertEquals(all.obj.keySet.toSet, typed.toSet)
    typed.foreach(n => assertEquals(all(n), schemaOf(n)))

  test("resources/read of an unknown tool or uri is a -32002 error naming the typed tools"):
    val missing =
      rpc("resources/read", ujson.Obj("uri" -> (OutputSchemaResources.UriPrefix + "nope")))
    assertEquals(missing("error")("code").num.toInt, -32002)
    assert(missing("error")("message").str.contains("find_usages"))
    val foreign = rpc("resources/read", ujson.Obj("uri" -> "file:///etc/passwd"))
    assertEquals(foreign("error")("code").num.toInt, -32002)

  test("output_schema tool: one tool by name, all without argument, error for unknown"):
    val tool = tools.find(_.name == "output_schema").getOrElse(fail("no output_schema tool"))
    assertEquals(tool.run(ujson.Obj("tool" -> "method_signature")), schemaOf("method_signature"))
    assertEquals(tool.run(ujson.Obj()).obj.keySet.toSet, typed.toSet)
    val e = intercept[Throwable](tool.run(ujson.Obj("tool" -> "nope")))
    assert(e.getMessage.contains("nope"))

  test("the lean default keeps tools/list small: output_schema adds one short definition"):
    val withTool = ujson.write(rpc("tools/list", ujson.Obj())("result")("tools")).length
    val own = tools
      .find(_.name == "output_schema")
      .map(t =>
        ujson
          .write(
            ujson
              .Obj("name" -> t.name, "description" -> t.description, "inputSchema" -> t.inputSchema)
          )
          .length
      )
    assert(own.exists(_ < 600), s"output_schema definition is $own chars")
    println(s"tools/list default: $withTool chars; output_schema definition: ${own.getOrElse(-1)}")

  test("find_usages result is still served and conforms when structured output is on"):
    val res = rpc(
      "tools/call",
      ujson.Obj("name" -> "find_usages", "arguments" -> ujson.Obj("symbol" -> Loc, "limit" -> 2)),
      structured = true
    )("result")
    assertEquals(
      SchemaConformance.violations(schemaOf("find_usages"), res("structuredContent")),
      Nil
    )
