package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** `members` (a `Lookup`) and `resolve_implicits` as typed tools. `members` declared entries are
  * bare names by default and objects when `detailed`; inherited entries are always objects.
  */
class MembersResolveImplicitsSchemaSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private def tool(name: String): Tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))
  private def call(name: String, args: (String, J.V)*): Json =
    tool(name).run(J.obj(args*))
  private def schemaOf(name: String): Json =
    tool(name).outputSchema.getOrElse(fail(s"$name has no outputSchema"))
  private def conforms(name: String, res: Json): Unit =
    assertEquals(SchemaConformance.violations(schemaOf(name), res), Nil)

  // A case class: declares accessors/copy, inherits from Product/Equals/Object.
  private val LookupType = "com/github/mercurievv/scalasemantic/mcp/FindUsagesResult#"

  // ---- members -----------------------------------------------------------------------------

  test("members: oneOf (result | not found | candidates), self-contained"):
    val s = schemaOf("members")
    assertEquals(
      s("oneOf").arr.toList.map(_("required").arr.toList.map(_.str).toSet).toSet,
      Set(Set("symbol", "name"), Set("symbol", "found"), Set("candidates", "note"))
    )
    assert(!J.write(s).contains("$ref"))
    println(s"members outputSchema: ${J.write(s).length} chars")

  test("members: default — declared entries are bare names"):
    val res = call("members", "symbol" -> LookupType)
    assert(res.obj.keySet.subsetOf(Set("symbol", "name", "declared", "inherited")))
    res("declared").arr.toList.foreach(d => assert(d.strOpt.isDefined, d.toString))
    conforms("members", res)

  test("members: detailed — declared entries carry kind, symbol and declaring type"):
    val res = call("members", "symbol" -> LookupType, "detailed" -> true)
    res("declared").arr.toList.foreach: d =>
      assertEquals(d.obj.keySet.toSet, Set("name", "kind", "symbol", "from"))
    conforms("members", res)

  // Indexed project types here re-declare what they inherit, so `inherited` is exercised at the
  // encoder/schema level: both entry shapes ({name, from} and detailed) must conform.
  test("members: inherited entries encode within the schema, optional kind/symbol dropped"):
    val schema = OutputSchema.of[Lookup[MembersOut]]
    val brief = MemberOut("size", None, None, "Base")
    val full = MemberOut("size", Some("METHOD"), Some("pkg/Base#size()."), "Base")
    List(
      MembersOut("pkg/Sub#", "Sub", Some(List(NameOr.Name("x"))), Some(List(brief))),
      MembersOut("pkg/Sub#", "Sub", Some(List(NameOr.Full(full))), Some(List(full)))
    ).foreach: m =>
      val json = TypedTool.encode(Lookup.Found(m): Lookup[MembersOut])
      assertEquals(SchemaConformance.violations(schema, json), Nil)
    assertEquals(TypedTool.encode(brief).obj.keySet.toSet, Set("name", "from"))

  test("members: `include` trims sections"):
    val res = call("members", "symbol" -> LookupType, "include" -> J.arr("declared"))
    assert(res.obj.contains("declared") && !res.obj.contains("inherited"))
    conforms("members", res)

  test("members: unresolved symbol and ambiguous file keep their shapes"):
    val missing = call("members", "symbol" -> "no/such/Type#")
    assertEquals(missing("found").bool, false)
    conforms("members", missing)
    val many = call(
      "members",
      "symbol" -> "mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/ToolResultParts.scala"
    )
    assert(many("candidates").arr.size > 1 && many("note").str.nonEmpty)
    conforms("members", many)

  // ---- resolve_implicits -------------------------------------------------------------------

  test("resolve_implicits: schema requires type + candidates; `chosen` optional"):
    val s = schemaOf("resolve_implicits")
    assertEquals(s("required").arr.toList.map(_.str).toSet, Set("type", "candidates"))
    assert(!J.write(s).contains("$ref"))
    println(s"resolve_implicits outputSchema: ${J.write(s).length} chars")

  test("resolve_implicits: lists the given definitions producing a type"):
    val res = call("resolve_implicits", "type" -> "io/circe/Encoder#")
    assertEquals(res("type").str, "io/circe/Encoder#")
    val candidates = res("candidates").arr.toList
    assert(candidates.nonEmpty)
    candidates.foreach(c => assertEquals(c.obj.keySet.toSet, Set("symbol", "type")))
    assert(res.obj.keySet.subsetOf(Set("type", "chosen", "candidates")))
    conforms("resolve_implicits", res)

  test("resolve_implicits: no candidates keeps `candidates` as an empty array, no `chosen`"):
    val res = call("resolve_implicits", "type" -> "no/such/Type#")
    assertEquals(res("candidates").arr.size, 0)
    assert(!res.obj.contains("chosen"))
    conforms("resolve_implicits", res)
