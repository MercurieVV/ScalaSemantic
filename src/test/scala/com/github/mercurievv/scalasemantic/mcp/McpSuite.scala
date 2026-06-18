package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Phase 4: MCP protocol + tool wiring, driven through the pure `Mcp.handle` against the fixtures
  * index. Also pins the token-discipline contract: lean by default, structured under `detailed`.
  */
class McpSuite extends munit.FunSuite:

  private val tools = McpTools.all(Analyzer(SemanticIndex.fromProject("target")))

  private val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"
  private val Render = "com/github/mercurievv/scalasemantic/fixtures/Sample.render()."
  private val Show = "com/github/mercurievv/scalasemantic/fixtures/Show#"

  private def req(
      method: String,
      params: ujson.Value,
      id: ujson.Value = ujson.Num(1)
  ): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)

  /** Invoke a tool and parse the JSON carried in its text content block. */
  private def call(name: String, args: ujson.Value): ujson.Value =
    val resp = Mcp.handle(req("tools/call", ujson.Obj("name" -> name, "arguments" -> args)), tools)
    val text = resp.getOrElse(fail("no response"))("result")("content")(0)("text").str
    ujson.read(text)

  test("initialize echoes the protocol version and advertises the server") {
    val r = Mcp.handle(req("initialize", ujson.Obj("protocolVersion" -> "2025-06-18")), tools).get
    assertEquals(r("result")("protocolVersion").str, "2025-06-18")
    assertEquals(r("result")("serverInfo")("name").str, Mcp.ServerName)
  }

  test("tools/list exposes all nine tools with schemas") {
    val r = Mcp.handle(req("tools/list", ujson.Obj()), tools).get
    val names = r("result")("tools").arr.map(_("name").str).toSet
    assertEquals(names.size, 9)
    assert(names.contains("find_usages"), names.toString)
    assert(names.contains("call_path"), names.toString)
    // every tool carries an object input schema
    assert(r("result")("tools").arr.forall(_("inputSchema")("type").str == "object"))
  }

  test("notifications get no response") {
    val n = ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized")
    assertEquals(Mcp.handle(n, tools), None)
  }

  test("unknown tool yields a JSON-RPC error") {
    val r = Mcp
      .handle(req("tools/call", ujson.Obj("name" -> "nope", "arguments" -> ujson.Obj())), tools)
      .get
    assert(r.obj.contains("error"), r.render())
  }

  test("find_usages returns a count and paged references") {
    val r = call("find_usages", ujson.Obj("symbol" -> Animal, "limit" -> 1))
    assert(r("referenceCount").num > 0)
    assert(r("references").arr.nonEmpty)
    // compact location form uri:line:col
    assert(r("references")(0).str.count(_ == ':') >= 2, r("references")(0).str)
  }

  test("method_signature is lean by default and structured only when detailed") {
    val lean = call("method_signature", ujson.Obj("symbol" -> Render))
    assertEquals(lean("signature").str, "def render[A](a: A)(implicit sh: Show[A]): String")
    assert(!lean.obj.contains("parameterLists"), "lean result must not expand parameter lists")

    val full = call("method_signature", ujson.Obj("symbol" -> Render, "detailed" -> true))
    assert(full.obj.contains("parameterLists"), full.render())
    assertEquals(full("parameterLists").arr.last("implicit").bool, true)
  }

  test("class_hierarchy reports known subtypes as display names by default") {
    val r = call("class_hierarchy", ujson.Obj("symbol" -> Animal))
    assertEquals(r("knownSubtypes").arr.map(_.str).toList, List("Dog", "Fish"))
  }

  test("resolve_implicits lists candidate givens for a type") {
    val r = call("resolve_implicits", ujson.Obj("type" -> Show))
    val types = r("candidates").arr.map(_("type").str).toSet
    assertEquals(types, Set("Show[Int]", "Show[List[A]]"))
  }

  test("call_path reports reachability and the method chain") {
    val a = "com/github/mercurievv/scalasemantic/fixtures/Calls.a()."
    val c = "com/github/mercurievv/scalasemantic/fixtures/Calls.c()."
    val r = call("call_path", ujson.Obj("from" -> a, "to" -> c))
    assertEquals(r("reachable").bool, true)
    assertEquals(r("path").arr.map(_.str).toList, List("a", "b", "c"))
  }

  test("process maps a request stream to responses, skipping blanks and notifications") {
    val in = Iterator(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
      "",
      """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
    )
    val out = Mcp.process(in, tools).toList
    // 4 lines in (one blank, one notification) → 2 responses out, with matching ids
    assertEquals(out.size, 2)
    assertEquals(ujson.read(out(0))("id").num, 1.0)
    assertEquals(ujson.read(out(1))("result")("tools").arr.size, 9)
  }
