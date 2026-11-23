package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Path
import java.nio.file.Paths

/** Typed tool arguments (ADR 0006, stage B), piloted on `find_symbol`: the `inputSchema` is derived
  * from the argument type, a bad request fails with one message naming the field, and unknown
  * arguments are ignored (and optionally logged). Uses this suite's own tool instances.
  */
class TypedInputSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tool: Tool = Mcp
    .toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
    .find(_.name == "find_symbol")
    .getOrElse(fail("no find_symbol"))

  private def run(args: (String, ujson.Value)*): ujson.Value = tool.run(ujson.Obj.from(args))

  test("inputSchema is derived from the argument type: required = non-Option fields"):
    val s = tool.inputSchema
    assertEquals(s("type").str, "object")
    assertEquals(s("required").arr.map(_.str).toSet, Set("query"))
    assertEquals(
      s("properties").obj.keySet.toSet,
      Set("query", "limit", "exact", "kind", "pathFilter", "metrics")
    )
    assertEquals(s("properties")("limit")("type").str, "integer")
    assertEquals(s("properties")("exact")("type").str, "boolean")
    assert(s("properties")("query")("description").str.nonEmpty)

  test("a typed call behaves as before: query, limit, exact"):
    val res = run("query" -> "Analyzer", "limit" -> 3)
    assertEquals(res("query").str, "Analyzer")
    assert(res("symbols").arr.size <= 3 && res("symbols").arr.nonEmpty)
    val exact = run("query" -> "FindUsagesResult", "exact" -> true)
    assert(exact("symbols").arr.forall(_("name").str == "FindUsagesResult"))

  test("a missing required argument names the field"):
    val e = intercept[RuntimeException](run("limit" -> 3))
    assert(e.getMessage.contains("find_symbol: invalid arguments"), e.getMessage)
    assert(e.getMessage.contains("query"), e.getMessage)

  test("a mistyped argument names the field"):
    val e = intercept[RuntimeException](run("query" -> "A", "limit" -> "many"))
    assert(e.getMessage.contains("`limit`"), e.getMessage)

  test("a non-positive limit is still rejected by validation"):
    val e = intercept[RuntimeException](run("query" -> "A", "limit" -> 0))
    assert(e.getMessage.toLowerCase.contains("limit"), e.getMessage)

  test("unknown arguments are ignored, and shared shaping arguments are not unknown"):
    val res = run("query" -> "Analyzer", "limit" -> 2, "bogus" -> true)
    assertEquals(res("query").str, "Analyzer")

  test("unknown arguments are logged only when the warning is switched on"):
    val key = "scalasemantic.warnUnknownArgs"
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val previous = Mcp.log.getAndSet(line => { val _ = seen.add(line) })
    try
      val _ = run("query" -> "Analyzer", "limit" -> 1, "bogus" -> 1)
      assert(seen.isEmpty, "off by default")
      val _ = System.setProperty(key, "true")
      val _ = run("query" -> "Analyzer", "limit" -> 1, "bogus" -> 1, "fields" -> ujson.Arr())
      assertEquals(seen.size, 1)
      val line = Option(seen.peek()).getOrElse("")
      assert(line.contains("find_symbol") && line.contains("bogus"), line)
      assert(!line.contains("fields"), "shared shaping args are never reported")
    finally
      val _ = System.clearProperty(key)
      Mcp.log.set(previous)

  test("JsonBridge keeps integers integral and nesting intact"):
    val j = JsonBridge.toCirce(
      ujson.Obj("a" -> 2, "b" -> 2.5, "c" -> ujson.Arr(true, ujson.Null, "x"), "d" -> ujson.Obj())
    )
    assertEquals(j.noSpaces, """{"a":2,"b":2.5,"c":[true,null,"x"],"d":{}}""")
