package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Path
import java.nio.file.Paths

/** `find_symbol` is the pilot for typed tool results: circe encodes the wire JSON, tapir derives
  * the `outputSchema`. These tests pin (1) the wire shape the hand-built `jobj` version produced
  * and (2) that real output conforms to the declared schema — so the two can't silently drift
  * apart.
  */
class FindSymbolOutputSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tool: Tool =
    Mcp
      .toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
      .find(_.name == "find_symbol")
      .getOrElse(fail("no find_symbol"))
  private def call(args: (String, ujson.Value)*): ujson.Value = tool.run(ujson.Obj.from(args))
  private val schema: ujson.Value =
    tool.outputSchema.getOrElse(fail("find_symbol has no outputSchema"))

  private def violations(s: ujson.Value, v: ujson.Value): List[String] =
    SchemaConformance.violations(s, v)

  test("declares a compact object outputSchema with required fields and descriptions"):
    assertEquals(schema("type").str, "object")
    assertEquals(schema("required").arr.map(_.str).toSet, Set("query", "count", "symbols"))
    val hit = schema("properties")("symbols")("items")
    assertEquals(hit("required").arr.map(_.str).toSet, Set("symbol", "name", "kind"))
    assert(hit("properties")("symbol")("description").str.nonEmpty)
    assert(!ujson.write(schema).contains("$ref"), "schema must be self-contained")

  test("outputSchema also allows the optional hints the server wrappers append"):
    val props = schema("properties").obj
    assert(props.contains("indexEmptyHint") && props.contains("coverageHint"))
    assert(!schema("required").arr.map(_.str).contains("indexEmptyHint"))

  test("wire shape: query/count/symbols, hit = symbol/name/kind only without metrics"):
    val res = call("query" -> "Analyzer", "limit" -> 5)
    assertEquals(res.obj.keySet, Set("query", "count", "symbols"))
    assertEquals(res("query").str, "Analyzer")
    assertEquals(res("count").num.toInt, res("symbols").arr.size)
    assert(res("symbols").arr.nonEmpty)
    res("symbols").arr.foreach(h => assertEquals(h.obj.keySet, Set("symbol", "name", "kind")))

  test("wire shape: metrics=true adds layer/centrality (None fields stay absent)"):
    val res = call("query" -> "Analyzer", "limit" -> 5, "metrics" -> true)
    val hits = res("symbols").arr.toList
    assert(hits.exists(_.obj.contains("layer")))
    hits.foreach: h =>
      assert(Set("symbol", "name", "kind", "layer", "centrality", "inCycle").exists(h.obj.contains))
      assert(h.obj.keySet.subsetOf(Set("symbol", "name", "kind", "layer", "centrality", "inCycle")))

  test("no match keeps `symbols` as an empty array (required, not dropped)"):
    val res = call("query" -> "NoSuchSymbolAnywhere_xyz")
    assertEquals(res("count").num.toInt, 0)
    assertEquals(res("symbols").arr.size, 0)

  test("real outputs conform to the declared outputSchema"):
    List(
      call("query" -> "Analyzer", "limit" -> 10),
      call("query" -> "Analyzer", "limit" -> 10, "metrics" -> true),
      call("query" -> "NoSuchSymbolAnywhere_xyz")
    ).foreach(res => assertEquals(violations(schema, res), Nil))

  test("schema stays small (it ships in every tools/list)"):
    val chars = ujson.write(schema).length
    println(s"find_symbol outputSchema: $chars chars")
    assert(chars < 2000, s"outputSchema is $chars chars")

  private def rpc(method: String, params: ujson.Value): ujson.Value =
    Mcp
      .handle(
        ujson.Obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> method, "params" -> params),
        List(tool),
        (_, _) => ()
      )
      .getOrElse(fail(s"no response to $method"))("result")

  test("tools/list advertises outputSchema; tools/call returns matching structuredContent"):
    val listed = rpc("tools/list", ujson.Obj())("tools").arr.toList.head
    assertEquals(listed("outputSchema"), schema)
    val res = rpc(
      "tools/call",
      ujson.Obj(
        "name" -> "find_symbol",
        "arguments" -> ujson.Obj("query" -> "Analyzer", "limit" -> 3)
      )
    )
    val text = ujson.read(res("content")(0)("text").str)
    assertEquals(res("structuredContent"), text)
    assertEquals(violations(schema, res("structuredContent")), Nil)
