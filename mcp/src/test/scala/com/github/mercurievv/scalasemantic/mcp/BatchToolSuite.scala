package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.Path
import java.nio.file.Paths

/** `batch`: several calls in one round trip, later calls fed from earlier results. Runs against
  * this suite's own tool instances (never `Mcp.handle`, which reads process-global state).
  */
class BatchToolSuite extends ScalaCheckSuite:

  private val root: Path = Paths.get(".")
  private val tools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private val batch: Tool = tools.find(_.name == "batch").getOrElse(fail("no batch tool"))

  private def call(tool: String, args: (String, ujson.Value)*): ujson.Value =
    ujson.Obj("tool" -> tool, "args" -> ujson.Obj.from(args))

  private def run(calls: ujson.Value*): List[ujson.Value] =
    batch.run(ujson.Obj("calls" -> ujson.Arr.from(calls))).obj("results").arr.toList

  // ---- the path language (properties) ------------------------------------------------------

  private def treeGen(depth: Int): Gen[ujson.Value] =
    val leaf = Gen.choose(0, 99).map(n => ujson.Num(n): ujson.Value)
    if depth == 0 then leaf
    else
      Gen.oneOf(
        leaf,
        Gen.listOfN(3, treeGen(depth - 1)).map(xs => ujson.Arr.from(xs): ujson.Value),
        Gen
          .listOfN(3, treeGen(depth - 1))
          .map(xs => ujson.Obj.from(List("a", "b", "c").zip(xs)): ujson.Value)
      )

  private def paths(v: ujson.Value): List[(List[String], ujson.Value)] =
    (Nil, v) :: (v match
      case a: ujson.Arr =>
        a.value.toList.zipWithIndex.flatMap((c, i) => paths(c).map((p, x) => (i.toString :: p, x)))
      case o: ujson.Obj =>
        o.value.toList.flatMap((k, c) => paths(c).map((p, x) => (k :: p, x)))
      case _ => Nil)

  property("every path into a JSON tree resolves to exactly the node it names"):
    forAll(treeGen(3))(t => paths(t).forall((p, x) => BatchTool.at(t, p) == Right(x)))

  property("a path that leaves the tree is an error, never a value"):
    forAll(treeGen(2))(t =>
      BatchTool.at(t, List("no-such-key")).isLeft || t.objOpt.isEmpty && t.arrOpt.isEmpty
    )

  // ---- behaviour ---------------------------------------------------------------------------

  test("independent calls run in order, each with its own ok/result"):
    val rs = run(
      call("find_symbol", "query" -> "Analyzer", "limit" -> 2),
      call("find_symbol", "query" -> "FindUsagesResult", "exact" -> true)
    )
    assertEquals(rs.map(_("ok").bool), List(true, true))
    assertEquals(rs.map(_("tool").str), List("find_symbol", "find_symbol"))
    assertEquals(rs(1)("result")("query").str, "FindUsagesResult")

  test("$ref feeds an earlier result into a later call (resolve-then-act)"):
    val rs = run(
      call("find_symbol", "query" -> "FindUsagesResult", "exact" -> true, "kind" -> "CLASS"),
      call("members", "symbol" -> ujson.Obj("$ref" -> "0.symbols.0.symbol"), "countOnly" -> true)
    )
    assertEquals(rs.map(_("ok").bool), List(true, true))
    assertEquals(rs(1)("result")("symbol").str, rs(0)("result")("symbols")(0)("symbol").str)
    assert(rs(1)("result")("counts")("declared").num > 0, "countOnly applied per call")

  test("a failing call does not stop the others, and the reason is reported"):
    val rs = run(call("no_such_tool"), call("find_symbol", "query" -> "Analyzer", "limit" -> 1))
    assertEquals(rs.map(_("ok").bool), List(false, true))
    assert(rs(0)("error").str.contains("no_such_tool"))

  test("a bad or failed reference fails only that call, naming the path"):
    val rs = run(
      call("no_such_tool"),
      call("members", "symbol" -> ujson.Obj("$ref" -> "0.symbols.0.symbol")),
      call("members", "symbol" -> ujson.Obj("$ref" -> "9.x")),
      call("find_symbol", "query" -> "Analyzer", "limit" -> 1),
      call("members", "symbol" -> ujson.Obj("$ref" -> "3.nope"))
    )
    assertEquals(rs.map(_("ok").bool), List(false, false, false, true, false))
    assert(rs(1)("error").str.contains("call 0 failed"))
    assert(rs(2)("error").str.contains("no earlier call"))
    assert(rs(4)("error").str.contains("no key 'nope'"))

  test("limits: at most 20 calls, and batch cannot call batch"):
    val tooMany = intercept[Throwable](run(List.fill(21)(call("get_workspace_root"))*))
    assert(tooMany.getMessage.contains("at most 20"))
    assert(run(call("batch", "calls" -> ujson.Arr()))(0)("error").str.contains("cannot call batch"))
