package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import com.github.mercurievv.scalasemantic.wire.api.*
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** `class_hierarchy` and `find_overloads` as typed tools (both `Lookup`-shaped): wire shape pinned,
  * real output conforms to the declared schema. `class_hierarchy` entries are display-name strings
  * by default and `{symbol,name,kind}` objects when `detailed` — the schema allows both.
  */
class ClassHierarchyFindOverloadsSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))
  private def call(name: String, args: (String, J.V)*): Json =
    tool(name).run(J.obj(args*))
  private def schemaOf(name: String): Json =
    tool(name).outputSchema.getOrElse(fail(s"$name has no outputSchema"))
  private def conforms(name: String, res: Json): Unit =
    assertEquals(SchemaConformance.violations(schemaOf(name), res), Nil)

  private val Lookup = SemSym.typeOf[Lookup[Any]]
  private val HierarchyKeys = Set(
    "symbol",
    "name",
    "layer",
    "centrality",
    "inCycle",
    "parents",
    "linearization",
    "knownSubtypes"
  )

  // ---- class_hierarchy ---------------------------------------------------------------------

  test("class_hierarchy: oneOf (result | not found | candidates), self-contained"):
    val s = schemaOf("class_hierarchy")
    assertEquals(
      s("oneOf").arr.toList.map(_("required").arr.toList.map(_.str).toSet).toSet,
      Set(Set("symbol", "name"), Set("symbol", "found"), Set("candidates", "note"))
    )
    assert(!J.write(s).contains("$ref"))
    println(s"class_hierarchy outputSchema: ${J.write(s).length} chars")

  test("class_hierarchy: default answer lists parents and known subtypes as display names"):
    val res = call("class_hierarchy", "symbol" -> Lookup)
    assert(res.obj.keySet.subsetOf(HierarchyKeys))
    assertEquals(res("name").str, "Lookup")
    val subs = res("knownSubtypes").arr.toList.map(_.str)
    assert(Set("Found", "Missing", "Ambiguous").subsetOf(subs.toSet), subs.toString)
    assert(res("parents").arr.nonEmpty)
    conforms("class_hierarchy", res)

  test("class_hierarchy: detailed expands related types to {symbol, name, kind}"):
    val res = call("class_hierarchy", "symbol" -> Lookup, "detailed" -> true)
    val first = res("knownSubtypes")(0)
    assertEquals(first.obj.keySet.toSet, Set("symbol", "name", "kind"))
    conforms("class_hierarchy", res)

  test("class_hierarchy: `include` trims sections"):
    val res = call("class_hierarchy", "symbol" -> Lookup, "include" -> J.arr("parents"))
    assert(res.obj.contains("parents") && !res.obj.contains("knownSubtypes"))
    assert(!res.obj.contains("linearization"))
    conforms("class_hierarchy", res)

  test("class_hierarchy: metrics adds layer/centrality (inCycle only when true)"):
    val res = call("class_hierarchy", "symbol" -> Lookup, "metrics" -> true)
    assert(res.obj.contains("layer") && res.obj.contains("centrality"))
    assert(res.obj.keySet.subsetOf(HierarchyKeys))
    conforms("class_hierarchy", res)

  test("class_hierarchy: unresolved symbol is {symbol, found:false}"):
    val res = call("class_hierarchy", "symbol" -> "no/such/Type#")
    assertEquals(res("found").bool, false)
    conforms("class_hierarchy", res)

  // ---- find_overloads ----------------------------------------------------------------------

  private val Overloaded =
    "com/github/mercurievv/scalasemantic/mcp/McpToolsSupport.argIdentifier()."

  test("find_overloads: oneOf schema; required = name + overloads for the result"):
    val s = schemaOf("find_overloads")
    assert(
      s("oneOf").arr.toList
        .exists(_("required").arr.toList.map(_.str).toSet == Set("name", "overloads"))
    )
    println(s"find_overloads outputSchema: ${J.write(s).length} chars")

  test("find_overloads: lists every overload as a rendered signature"):
    val res = call("find_overloads", "symbol" -> Overloaded)
    assertEquals(res("name").str, "argIdentifier")
    assert(res("overloads").arr.size >= 2)
    assert(res.obj.keySet.subsetOf(Set("name", "overloads", "inheritedOverloads")))
    conforms("find_overloads", res)

  test("find_overloads: unresolved symbol and ambiguous file keep their shapes"):
    val missing = call("find_overloads", "symbol" -> "no/such/Thing.nothing().")
    // A method-shaped symbol that resolves to nothing is not "not found": the tool answers with the
    // parsed name and no overloads (unchanged wire behaviour).
    assertEquals(missing("name").str, "nothing")
    assertEquals(missing("overloads").arr.size, 0)
    conforms("find_overloads", missing)
    val many = call(
      "find_overloads",
      "symbol" -> "mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/Mcp.scala"
    )
    assert(many("candidates").arr.size > 1 && many("note").str.nonEmpty)
    conforms("find_overloads", many)
