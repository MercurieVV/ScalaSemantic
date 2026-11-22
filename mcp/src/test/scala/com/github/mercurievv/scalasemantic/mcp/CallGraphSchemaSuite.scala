package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Path
import java.nio.file.Paths

/** `call_path`, `method_call_hierarchy` and `trace_implicit_chain` as typed tools. The last two
  * return recursive trees, so their schemas carry the tree type once under `$defs`.
  */
class CallGraphSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))
  private def call(name: String, args: (String, ujson.Value)*): ujson.Value =
    tool(name).run(ujson.Obj.from(args))
  private def schemaOf(name: String): ujson.Value =
    tool(name).outputSchema.getOrElse(fail(s"$name has no outputSchema"))
  private def conforms(name: String, res: ujson.Value): Unit =
    assertEquals(SchemaConformance.violations(schemaOf(name), res), Nil)

  private val Pkg = "com/github/mercurievv/scalasemantic/mcp/"
  private val Caller = Pkg + "OutputSchemaResources.tool()." // calls McpToolsSupport.tool directly
  private val Callee = Pkg + "McpToolsSupport.tool()."

  // ---- call_path ---------------------------------------------------------------------------

  test("call_path: schema requires from/to/reachable; path and edges optional"):
    val s = schemaOf("call_path")
    assertEquals(s("required").arr.toList.map(_.str).toSet, Set("from", "to", "reachable"))
    println(s"call_path outputSchema: ${ujson.write(s).length} chars")

  test("call_path: a direct call is reachable, with the path (edges only when detailed)"):
    val res = call("call_path", "from" -> Caller, "to" -> Callee)
    assertEquals(res("reachable").bool, true)
    assertEquals(res("path").arr.toList.map(_.str), List("tool", "tool"))
    assert(!res.obj.contains("edges"))
    conforms("call_path", res)
    val detailed = call("call_path", "from" -> Caller, "to" -> Callee, "detailed" -> true)
    assert(detailed("edges").arr.toList.forall(_.str.contains("@")))
    conforms("call_path", detailed)

  test("call_path: unreachable keeps reachable=false and drops path"):
    val res = call("call_path", "from" -> Callee, "to" -> Caller)
    assertEquals(res("reachable").bool, false)
    assert(!res.obj.contains("path"))
    conforms("call_path", res)

  // ---- method_call_hierarchy (recursive) ---------------------------------------------------

  test("method_call_hierarchy: recursive node type is defined once under $defs"):
    val s = schemaOf("method_call_hierarchy")
    assertEquals(s("properties")("hierarchy")("$ref").str, "#/$defs/CallNodeOut")
    assertEquals(
      s("$defs")("CallNodeOut")("properties")("children")("items")("$ref").str,
      "#/$defs/CallNodeOut"
    )
    println(s"method_call_hierarchy outputSchema: ${ujson.write(s).length} chars")

  test("method_call_hierarchy: callees tree nests and conforms"):
    val res = call("method_call_hierarchy", "symbol" -> Caller, "depth" -> 2)
    assertEquals(res("direction").str, "callees")
    assertEquals(res("hierarchy")("symbol").str, Caller)
    val kids = res("hierarchy")("children").arr.toList
    assert(kids.nonEmpty && kids.forall(_.obj.contains("at")))
    conforms("method_call_hierarchy", res)

  test("method_call_hierarchy: callers direction conforms"):
    val res = call("method_call_hierarchy", "symbol" -> Callee, "direction" -> "callers")
    assertEquals(res("direction").str, "callers")
    conforms("method_call_hierarchy", res)

  // ---- trace_implicit_chain (recursive) ----------------------------------------------------

  test("trace_implicit_chain: tree type under $defs; required = type + steps"):
    val s = schemaOf("trace_implicit_chain")
    assertEquals(s("required").arr.toList.map(_.str).toSet, Set("type", "steps"))
    assertEquals(s("properties")("resolved")("$ref").str, "#/$defs/ImplicitTreeOut")
    assert(s("$defs").obj.contains("ImplicitTreeOut"))
    println(s"trace_implicit_chain outputSchema: ${ujson.write(s).length} chars")

  test("trace_implicit_chain: steps list the givens producing a type"):
    val res = call("trace_implicit_chain", "type" -> "io/circe/Encoder#")
    assertEquals(res("type").str, "io/circe/Encoder#")
    val steps = res("steps").arr.toList
    assert(steps.nonEmpty)
    steps.foreach(st => assert(st.obj.contains("given") && st.obj.contains("type")))
    conforms("trace_implicit_chain", res)

  test("trace_implicit_chain: a nested resolution tree encodes within the schema"):
    val leaf = ImplicitTreeOut("B", Some("pkg/b."), None, None, None, None)
    val tree = ImplicitTreeOut(
      "A",
      None,
      Some(true),
      None,
      Some(List(ImplicitCandidateOut("pkg/a1.", "A"), ImplicitCandidateOut("pkg/a2.", "A"))),
      Some(List(leaf.copy(children = Some(List(leaf.copy(cycle = Some(true)))))))
    )
    val json = TypedTool.encode(ImplicitChainResult("pkg/A#", Some("A"), Nil, Some(tree)))
    assertEquals(SchemaConformance.violations(schemaOf("trace_implicit_chain"), json), Nil)
    assertEquals(json("resolved")("children")(0)("children")(0)("cycle").bool, true)
