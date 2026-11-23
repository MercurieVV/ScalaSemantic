package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** `find_usages` and `method_signature` as typed tools: wire shape stays what the hand-built `jobj`
  * versions produced, real output conforms to the declared `outputSchema`, and the shared `Lookup`
  * union (result | not found | candidates) is advertised as `oneOf`.
  */
class FindUsagesMethodSignatureSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))
  private def call(name: String, args: (String, J.V)*): Json =
    tool(name).run(J.obj(args*))
  private def schemaOf(name: String): Json =
    tool(name).outputSchema.getOrElse(fail(s"$name has no outputSchema"))
  private def conforms(name: String, res: Json): Unit =
    assertEquals(SchemaConformance.violations(schemaOf(name), res), Nil)

  private val Loc = "com/github/mercurievv/scalasemantic/mcp/McpToolsSupport.loc()."
  private val CaseClass = "com/github/mercurievv/scalasemantic/wire/api/NotFoundResult#"
  private val FindUsagesKeys = Set(
    "symbol",
    "name",
    "definitions",
    "referenceCount",
    "references",
    "nextOffset",
    "definitionsWithContext",
    "referencesWithContext",
    "related"
  )

  // ---- find_usages -------------------------------------------------------------------------

  test("find_usages: compact self-contained schema, required = symbol/name/referenceCount"):
    val s = schemaOf("find_usages")
    assertEquals(s("required").arr.toList.map(_.str).toSet, Set("symbol", "name", "referenceCount"))
    assertEquals(s("properties").obj.keySet.toSet.intersect(FindUsagesKeys), FindUsagesKeys)
    assert(!J.write(s).contains("$ref"))
    val chars = J.write(s).length
    println(s"find_usages outputSchema: $chars chars")
    assert(chars < 3000, s"outputSchema is $chars chars")

  test("find_usages: default call returns only declared keys, definitions + references"):
    val res = call("find_usages", "symbol" -> Loc)
    assert(res.obj.keySet.subsetOf(FindUsagesKeys))
    assertEquals(res("symbol").str, Loc)
    assertEquals(res("name").str, "loc")
    assert(res("referenceCount").num.toInt > 0)
    assert(res.obj.contains("definitions") && res.obj.contains("references"))
    conforms("find_usages", res)

  test("find_usages: `include` drops sections; referenceCount always stays"):
    val res = call("find_usages", "symbol" -> Loc, "include" -> J.arr("definitions"))
    assert(res.obj.contains("definitions") && res.obj.contains("referenceCount"))
    assert(!res.obj.contains("references") && !res.obj.contains("nextOffset"))
    conforms("find_usages", res)

  test("find_usages: paging — nextOffset appears while more references remain"):
    val first = call("find_usages", "symbol" -> Loc, "limit" -> 1)
    assertEquals(first("references").arr.size, 1)
    assertEquals(first("nextOffset").num.toInt, 1)
    val second = call("find_usages", "symbol" -> Loc, "limit" -> 1, "offset" -> 1)
    assertNotEquals(second("references")(0).str, first("references")(0).str)
    conforms("find_usages", first)
    conforms("find_usages", second)

  test("find_usages: contextLines adds *WithContext hits (location + context lines)"):
    val res = call("find_usages", "symbol" -> Loc, "limit" -> 2, "contextLines" -> 1)
    val hits = res("referencesWithContext").arr.toList
    assert(hits.nonEmpty)
    hits.foreach(h => assert(h("location").str.nonEmpty && h("context").arr.nonEmpty))
    conforms("find_usages", res)

  test("find_usages: a case class carries `related` construction groups"):
    val res = call("find_usages", "symbol" -> CaseClass)
    val groups = res("related").arr.toList
    assert(groups.nonEmpty)
    groups.foreach: g =>
      assert(g("kind").str.nonEmpty && g("symbol").str.nonEmpty)
      assert(g("locationCount").num.toInt >= g("locations").arr.size)
    conforms("find_usages", res)

  // ---- method_signature --------------------------------------------------------------------

  test("method_signature: schema is a oneOf (result | not found | candidates), hints once"):
    val s = schemaOf("method_signature")
    val alts = s("oneOf").arr.toList
    assertEquals(alts.size, 3)
    assertEquals(
      alts.map(_("required").arr.toList.map(_.str).toSet).toSet,
      Set(Set("symbol", "signature"), Set("symbol", "found"), Set("candidates", "note"))
    )
    assert(s("properties").obj.contains("coverageHint"))
    assert(!J.write(s).contains("$ref"))
    println(s"method_signature outputSchema: ${J.write(s).length} chars")

  test("method_signature: plain call is just symbol + signature"):
    val res = call("method_signature", "symbol" -> Loc)
    assertEquals(res.obj.keySet.toSet, Set("symbol", "signature"))
    assert(res("signature").str.contains("loc"))
    conforms("method_signature", res)

  test("method_signature: detailed adds parameterLists (implicit flag) and returnType"):
    val res = call("method_signature", "symbol" -> Loc, "detailed" -> true)
    assert(res.obj.contains("returnType") && res.obj.contains("parameterLists"))
    val list = res("parameterLists")(0)
    assertEquals(list("implicit").bool, false)
    assertEquals(list("params")(0)("name").str, "l")
    assert(list("params")(0)("type").str.nonEmpty)
    assert(!res.obj.contains("typeParameters"), "no type parameters -> field absent")
    conforms("method_signature", res)

  test("method_signature: unresolved symbol is {symbol, found:false}"):
    val res = call("method_signature", "symbol" -> "no/such/Thing.nothing().")
    assertEquals(res("found").bool, false)
    assertEquals(res("symbol").str, "no/such/Thing.nothing().")
    conforms("method_signature", res)

  test("method_signature: a file defining many methods answers {candidates, note}"):
    val res = call(
      "method_signature",
      "symbol" -> "mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/Mcp.scala"
    )
    assert(res("candidates").arr.size > 1)
    assert(res("note").str.nonEmpty)
    conforms("method_signature", res)

  // ---- wire ---------------------------------------------------------------------------------

  test("tools/list advertises both outputSchemas and tools/call returns structuredContent"):
    def rpc(method: String, params: Json): Json =
      Mcp
        .handle(
          J.obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> method, "params" -> params),
          tools,
          (_, _) => (),
          structured = true
        )
        .getOrElse(fail(s"no response to $method"))("result")
    val listed =
      rpc("tools/list", J.obj())("tools").arr.toList.map(t => t("name").str -> t).toMap
    List("find_usages", "method_signature").foreach: n =>
      assertEquals(listed(n)("outputSchema"), schemaOf(n))
    val res = rpc(
      "tools/call",
      J.obj("name" -> "find_usages", "arguments" -> J.obj("symbol" -> Loc, "limit" -> 1))
    )
    assertEquals(res("structuredContent"), J.read(res("content")(0)("text").str))
