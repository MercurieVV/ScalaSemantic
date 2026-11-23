package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import io.circe.Json
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.Path
import java.nio.file.Paths

import ResultShaping.Spec

/** `fields` / `countOnly` / `limit` on any tool's result: laws on the pure function (properties),
  * then the same arguments through the real `tools/call` path.
  */
class ResultShapingSuite extends ScalaCheckSuite:

  // ---- generators --------------------------------------------------------------------------

  private val keyGen: Gen[String] =
    Gen.oneOf("a", "b", "c", "d", "e", "found", "note") // two of them are control keys
  private val listGen: Gen[Json] =
    Gen
      .listOf(Gen.choose(0, 50))
      .map(xs => J.arrOf(xs.map(i => (J.num(i): Json))))
  private val scalarGen: Gen[Json] =
    Gen.oneOf(
      Gen.alphaStr.map(s => J.str(s): Json),
      Gen.choose(0, 99).map(n => J.num(n): Json)
    )
  private val objGen: Gen[Json] =
    Gen
      .listOf(Gen.zip(keyGen, Gen.oneOf(listGen, scalarGen)))
      .map(kvs => J.objOf(kvs.distinctBy(_._1)))

  private def entries(o: Json): List[(String, Json)] = o.obj.toList
  private def arraysOf(o: Json): List[(String, List[Json])] =
    entries(o).flatMap((k, v) => v.asArray.map(a => k -> a.toList))

  // ---- laws --------------------------------------------------------------------------------

  property("no arguments: the result is returned untouched"):
    forAll(objGen)(o => ResultShaping.shape(Spec(None, false, None), o) == o)

  property("limit n: every list has min(size, n) leading items; truncated reports the cut lists"):
    forAll(objGen, Gen.choose(1, 10)): (o, n) =>
      val shaped = ResultShaping.shape(Spec(Some(n), false, None), o)
      val before = arraysOf(o).toMap
      val after = arraysOf(shaped).toMap
      val cut = before.filter(_._2.size > n).view.mapValues(_.size).toMap
      val reported =
        shaped.obj.get("truncated").map(t => entries(t).map((k, v) => k -> v.num.toInt).toMap)
      before.keySet == after.keySet &&
      before.forall((k, xs) => after(k) == xs.take(n)) &&
      reported.getOrElse(Map.empty[String, Int]) == cut

  property("limit n: nothing but lists and `truncated` changes"):
    forAll(objGen, Gen.choose(1, 10)): (o, n) =>
      val shaped = ResultShaping.shape(Spec(Some(n), false, None), o)
      val scalars = entries(o).filterNot((_, v) => v.isArray)
      scalars.forall((k, v) => shaped.obj.get(k).contains(v))

  property("countOnly: no list survives, counts holds every original size"):
    forAll(objGen): o =>
      val shaped = ResultShaping.shape(Spec(None, true, None), o)
      val sizes = arraysOf(o).map((k, xs) => k -> xs.size).toMap
      val counts =
        shaped.obj.get("counts").map(c => entries(c).map((k, v) => k -> v.num.toInt).toMap)
      arraysOf(shaped).isEmpty && counts.getOrElse(Map.empty[String, Int]) == sizes

  property("fields: only requested and control keys remain, with their values unchanged"):
    forAll(objGen, Gen.someOf("a", "b", "c", "d", "e")): (o, wanted) =>
      val wantedKeys = wanted.toList
      val shaped = ResultShaping.shape(
        Spec(None, false, if wantedKeys.isEmpty then None else Some(wantedKeys)),
        o
      )
      val allowed = wantedKeys.toSet ++ ResultShaping.ControlKeys
      wantedKeys.isEmpty && shaped == o ||
      (entries(shaped).forall((k, v) => allowed(k) && o.obj.get(k).contains(v)) &&
        entries(o).filter((k, _) => allowed(k)).forall((k, _) => shaped.obj.contains(k)))

  // ---- projection into lists ---------------------------------------------------------------

  test("fields: `list.key` projects the items of a list, control keys survive"):
    val in = J.read("""{"items":[{"a":1,"b":2},{"a":3}],"x":1,"found":false}""")
    val out = ResultShaping.shape(Spec(None, false, Some(List("items.a"))), in)
    assertEquals(out, J.read("""{"items":[{"a":1},{"a":3}],"found":false}"""))

  // ---- through the real tools/call path ----------------------------------------------------

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val Members = "com/github/mercurievv/scalasemantic/mcp/FindUsagesResult#"

  // Straight to the function `tools/call` uses, with this suite's own tool instances: going through
  // `Mcp.handle` would consult the process-global `Mcp` state (`activeTools`), which other suites
  // replace, making the answer depend on suite order.
  private def call(name: String, args: (String, J.V)*): Json =
    val tool = tools.find(_.name == name).getOrElse(fail(s"no $name"))
    Mcp.shapedResult(tool, J.obj(args*), structured = true)

  private def payload(res: Json): Json = J.read(res("content")(0)("text").str)

  test("countOnly: members answer shrinks to counts; no structuredContent for a shaped result"):
    val full = payload(call("members", "symbol" -> Members))
    val res = call("members", "symbol" -> Members, "countOnly" -> true)
    val shaped = payload(res)
    assert(!shaped.obj.contains("declared"))
    assertEquals(shaped("counts")("declared").num.toInt, full("declared").arr.size)
    assert(!res.obj.contains("structuredContent"), "shaped results must not claim the schema")

  test("fields: keeps only what was asked for"):
    assertEquals(
      payload(call("members", "symbol" -> Members, "fields" -> J.arr("name"))).obj.keySet.toSet,
      Set("name")
    )

  test("fields on a miss: the control key `found` still tells the caller it was a miss"):
    val shaped =
      payload(call("members", "symbol" -> "no/such/Type#", "fields" -> J.arr("name")))
    assertEquals(shaped("found").bool, false)

  test("limit: caps lists and reports the original size"):
    val full = payload(call("members", "symbol" -> Members))
    val shaped = payload(call("members", "symbol" -> Members, "limit" -> 2))
    assertEquals(shaped("declared").arr.size, 2)
    assertEquals(shaped("truncated")("declared").num.toInt, full("declared").arr.size)

  test("limit is left to tools that have their own: find_symbol pages itself, no `truncated`"):
    val shaped = payload(call("find_symbol", "query" -> "Analyzer", "limit" -> 3))
    assert(shaped("symbols").arr.size <= 3)
    assert(!shaped.obj.contains("truncated"))
