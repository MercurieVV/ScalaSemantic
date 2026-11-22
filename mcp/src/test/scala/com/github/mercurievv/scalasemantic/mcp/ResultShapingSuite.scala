package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
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
  private val listGen: Gen[ujson.Value] =
    Gen
      .listOf(Gen.choose(0, 50))
      .map(xs => ujson.Arr.from(xs.map(i => (ujson.Num(i): ujson.Value))))
  private val scalarGen: Gen[ujson.Value] =
    Gen.oneOf(
      Gen.alphaStr.map(s => ujson.Str(s): ujson.Value),
      Gen.choose(0, 99).map(n => ujson.Num(n): ujson.Value)
    )
  private val objGen: Gen[ujson.Obj] =
    Gen
      .listOf(Gen.zip(keyGen, Gen.oneOf(listGen, scalarGen)))
      .map(kvs => ujson.Obj.from(kvs.distinctBy(_._1)))

  private def entries(o: ujson.Value): List[(String, ujson.Value)] = o.obj.toList
  private def arraysOf(o: ujson.Value): List[(String, List[ujson.Value])] =
    entries(o).collect { case (k, a: ujson.Arr) => k -> a.value.toList }

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
      val scalars = entries(o).filter { case (_, _: ujson.Arr) => false; case _ => true }
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
    val in = ujson.read("""{"items":[{"a":1,"b":2},{"a":3}],"x":1,"found":false}""")
    val out = ResultShaping.shape(Spec(None, false, Some(List("items.a"))), in)
    assertEquals(out, ujson.read("""{"items":[{"a":1},{"a":3}],"found":false}"""))

  // ---- through the real tools/call path ----------------------------------------------------

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val Members = "com/github/mercurievv/scalasemantic/mcp/FindUsagesResult#"

  private def call(name: String, args: (String, ujson.Value)*): ujson.Value =
    val res = Mcp
      .handle(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> 1,
          "method" -> "tools/call",
          "params" -> ujson.Obj("name" -> name, "arguments" -> ujson.Obj.from(args))
        ),
        tools,
        (_, _) => (),
        true
      )
      .getOrElse(fail("no response"))("result")
    res

  private def payload(res: ujson.Value): ujson.Value = ujson.read(res("content")(0)("text").str)

  test("countOnly: members answer shrinks to counts; no structuredContent for a shaped result"):
    val full = payload(call("members", "symbol" -> Members))
    val res = call("members", "symbol" -> Members, "countOnly" -> true)
    val shaped = payload(res)
    assert(!shaped.obj.contains("declared"))
    assertEquals(shaped("counts")("declared").num.toInt, full("declared").arr.size)
    assert(!res.obj.contains("structuredContent"), "shaped results must not claim the schema")

  test("fields: keeps only what was asked for"):
    assertEquals(
      payload(call("members", "symbol" -> Members, "fields" -> ujson.Arr("name"))).obj.keySet.toSet,
      Set("name")
    )

  test("fields on a miss: the control key `found` still tells the caller it was a miss"):
    val shaped =
      payload(call("members", "symbol" -> "no/such/Type#", "fields" -> ujson.Arr("name")))
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
