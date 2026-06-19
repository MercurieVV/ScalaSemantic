package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Phase 4: MCP protocol + tool wiring, driven through the pure `Mcp.handle` against the fixtures
  * index. Also pins the token-discipline contract: lean by default, structured under `detailed`.
  */
class McpSuite extends munit.FunSuite:

  private val tools = McpTools.all(Analyzer(SemanticIndex.fromProject(".")))

  private val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"
  private val Robot = "com/github/mercurievv/scalasemantic/fixtures/Robot#"
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

  test("initialize echoes the protocol version, advertises the server, and ships instructions") {
    val r = Mcp.handle(req("initialize", ujson.Obj("protocolVersion" -> "2025-06-18")), tools).get
    assertEquals(r("result")("protocolVersion").str, "2025-06-18")
    assertEquals(r("result")("serverInfo")("name").str, Mcp.ServerName)
    // instructions must steer the model to prefer these tools over grep
    val instr = r("result")("instructions").str
    assert(instr.toLowerCase.contains("grep"), instr)
    assert(instr.contains("find_symbol"), instr)
  }

  test("tools/list exposes all ten tools with schemas") {
    val r = Mcp.handle(req("tools/list", ujson.Obj()), tools).get
    val names = r("result")("tools").arr.map(_("name").str).toSet
    assertEquals(names.size, 10)
    assert(names.contains("find_symbol"), names.toString)
    assert(names.contains("find_usages"), names.toString)
    assert(names.contains("call_path"), names.toString)
    // every tool carries an object input schema
    assert(r("result")("tools").arr.forall(_("inputSchema")("type").str == "object"))
  }

  test("find_symbol resolves a plain name to ranked SemanticDB symbols") {
    val r = call("find_symbol", ujson.Obj("query" -> "Animal"))
    assert(r("count").num > 0)
    val syms = r("symbols").arr.map(_("symbol").str)
    assert(syms.contains(Animal), syms.toString)
    // exact match ranks first
    assertEquals(r("symbols")(0)("name").str, "Animal")
  }

  test("find_symbol narrows by kind, exact, and pathFilter") {
    val byKind = call("find_symbol", ujson.Obj("query" -> "Animal", "kind" -> "TRAIT"))
    val kinds = byKind("symbols").arr.map(_("kind").str).toSet
    assertEquals(kinds, Set("TRAIT"))

    val exact = call("find_symbol", ujson.Obj("query" -> "Animal", "exact" -> true))
    assert(exact("symbols").arr.forall(_("name").str == "Animal"), exact.render())

    val scoped =
      call("find_symbol", ujson.Obj("query" -> "Animal", "kind" -> "TRAIT", "pathFilter" -> "*compat*"))
    val syms = scoped("symbols").arr.map(_("symbol").str)
    assertEquals(syms.toList, List("com/github/mercurievv/scalasemantic/compat/Animal#"))
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

  test("find_usages include selects sections; referenceCount always stays") {
    val full = call("find_usages", ujson.Obj("symbol" -> Animal))
    assert(full.obj.contains("definitions") && full.obj.contains("references"), full.render())

    val countOnly = call("find_usages", ujson.Obj("symbol" -> Animal, "include" -> ujson.Arr()))
    assert(!countOnly.obj.contains("definitions"), countOnly.render())
    assert(!countOnly.obj.contains("references"), countOnly.render())
    assert(countOnly("referenceCount").num > 0, "count must survive any include")

    val refsOnly =
      call("find_usages", ujson.Obj("symbol" -> Animal, "include" -> ujson.Arr("references")))
    assert(!refsOnly.obj.contains("definitions"), refsOnly.render())
    assert(refsOnly("references").arr.nonEmpty, refsOnly.render())
  }

  test("find_usages pathFilter scopes references by document-uri glob") {
    val compat = "com/github/mercurievv/scalasemantic/compat/Animal#"
    val all = call("find_usages", ujson.Obj("symbol" -> compat))
    val scoped = call("find_usages", ujson.Obj("symbol" -> compat, "pathFilter" -> "*scala-3*"))
    assert(scoped("referenceCount").num < all("referenceCount").num, "filter must drop refs")
    assert(scoped("references").arr.forall(_.str.contains("scala-3")), scoped.render())
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

  test("class_hierarchy include selects sections and pathFilter scopes subtypes") {
    val full = call("class_hierarchy", ujson.Obj("symbol" -> Animal))
    assert(full.obj.contains("knownSubtypes"), full.render())

    val only =
      call("class_hierarchy", ujson.Obj("symbol" -> Animal, "include" -> ujson.Arr("knownSubtypes")))
    assert(!only.obj.contains("parents") && !only.obj.contains("linearization"), only.render())
    assertEquals(only("knownSubtypes").arr.map(_.str).toList, List("Dog", "Fish"))

    // Dog/Fish live under fixtures → a compat glob empties the list, dropping the section.
    val scoped = call("class_hierarchy", ujson.Obj("symbol" -> Animal, "pathFilter" -> "*compat*"))
    assert(!scoped.obj.contains("knownSubtypes"), scoped.render())
  }

  test("members include selects sections and pathFilter scopes members") {
    val full = call("members", ujson.Obj("symbol" -> Robot))
    assert(full.obj.contains("inherited"), full.render())

    val declOnly =
      call("members", ujson.Obj("symbol" -> Robot, "include" -> ujson.Arr("declared")))
    assert(!declOnly.obj.contains("inherited"), declOnly.render())

    val scoped = call("members", ujson.Obj("symbol" -> Robot, "pathFilter" -> "*compat*"))
    assert(!scoped.obj.contains("declared") && !scoped.obj.contains("inherited"), scoped.render())
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
    assertEquals(ujson.read(out(1))("result")("tools").arr.size, 10)
  }
