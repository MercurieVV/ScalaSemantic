package com.github.mercurievv.scalasemantic.mcp

import scala.util.Try

/** `batch`: several tool calls in one round trip, with later calls able to use earlier results.
  *
  * Why: in real transcripts the dominant pattern is not a smart multi-step query but the same tool
  * called again and again (`annotated_source` → `annotated_source` alone is 800+ pairs), plus
  * resolve-then-act (`find_symbol` → `symbol_source`). Each call costs a model turn; a batch costs
  * one.
  *
  * Call shape: `{"calls": [{"tool": "find_symbol", "args": {"query": "Foo"}}, {"tool":
  * "symbol_source", "args": {"symbol": {"$ref": "0.symbols.0.symbol"}}}]}`. An argument that is
  * exactly `{"$ref": path}` is replaced by the value at `path` in an earlier call's result:
  * `index.key.index…` (a segment is a key for an object, a position for a list). Keys containing a
  * dot are not addressable.
  *
  * One failing call does not stop the others; it reports `ok:false` with the reason, and a later
  * call that references it fails with a clear message. Per-call `fields` / `countOnly` / `limit`
  * ([[ResultShaping]]) apply. `batch` cannot call `batch`.
  */
object BatchTool:

  val MaxCalls: Int = 20

  private val Description: String =
    "Run several tool calls in one round trip (max 20): calls=[{tool, args}]. An arg that is exactly " +
      "{\"$ref\": \"0.symbols.0.symbol\"} is replaced by that path in an earlier call's result (index.key.index). " +
      "Returns {results:[{tool, ok, result|error}]}; a failing call does not stop the rest. " +
      "fields/countOnly/limit work per call."

  def tool(all: List[Tool]): Tool =
    val byName: Map[String, Tool] = all.map(t => t.name -> t).toMap
    McpToolsSupport.tool(
      "batch",
      Description,
      List(("calls", "array", "[{tool, args}], at most 20; run in order")),
      List("calls")
    ) { a =>
      val calls = a.obj.get("calls") match
        case Some(arr: ujson.Arr) => arr.value.toList
        case _ => McpToolsSupport.error("`calls` must be an array of {tool, args}")
      if calls.size > MaxCalls then
        McpToolsSupport.error(s"at most $MaxCalls calls per batch (got ${calls.size})")
      val results =
        calls.foldLeft(Vector.empty[ujson.Value])((done, c) => done :+ runOne(byName, c, done))
      ujson.Obj("results" -> ujson.Arr.from(results))
    }

  private def runOne(
      byName: Map[String, Tool],
      call: ujson.Value,
      done: Vector[ujson.Value]
  ): ujson.Value =
    val name = call.objOpt.flatMap(_.get("tool")).flatMap(_.strOpt).getOrElse("")
    val outcome: Either[String, ujson.Value] =
      for
        tool <-
          if name == "batch" then Left("batch cannot call batch")
          else byName.get(name).toRight(s"unknown tool '$name'")
        args <- resolveRefs(call.objOpt.flatMap(_.get("args")).getOrElse(ujson.Obj()), done)
        result <- Try(
          ResultShaping.shape(ResultShaping.specOf(tool, args), tool.run(args))
        ).toEither.left
          .map(t => Option(t.getMessage).getOrElse(t.getClass.getName))
      yield result
    outcome.fold(
      e => ujson.Obj("tool" -> name, "ok" -> false, "error" -> e),
      r => ujson.Obj("tool" -> name, "ok" -> true, "result" -> r)
    )

  // ---- references --------------------------------------------------------------------------

  /** Replaces every `{"$ref": path}` in `v` with the value `path` addresses in `done`. */
  private[mcp] def resolveRefs(
      v: ujson.Value,
      done: Vector[ujson.Value]
  ): Either[String, ujson.Value] =
    v match
      case o: ujson.Obj if o.value.size == 1 && o.value.contains("$ref") =>
        o.value("$ref").strOpt.toRight("$ref must be a string").flatMap(resolvePath(_, done))
      case o: ujson.Obj =>
        sequence(o.value.toList.map((k, x) => resolveRefs(x, done).map(k -> _)))
          .map(ujson.Obj.from(_))
      case a: ujson.Arr =>
        sequence(a.value.toList.map(resolveRefs(_, done))).map(ujson.Arr.from(_))
      case other => Right(other)

  /** `"2.symbols.0.symbol"`: the result of call 2, then key `symbols`, position 0, key `symbol`. */
  private[mcp] def resolvePath(
      path: String,
      done: Vector[ujson.Value]
  ): Either[String, ujson.Value] =
    path.split('.').toList match
      case head :: rest =>
        for
          idx <- head.toIntOption
            .filter(i => i >= 0 && i < done.size)
            .toRight(s"$$ref '$path': no earlier call '$head' (have ${done.size})")
          entry <- done.lift(idx).toRight(s"$$ref '$path': no earlier call")
          result <-
            if entry.obj.get("ok").contains(ujson.Bool(true)) then Right(entry("result"))
            else Left(s"$$ref '$path': call $idx failed")
          value <- at(result, rest).left.map(e => s"$$ref '$path': $e")
        yield value
      case Nil => Left("empty $ref")

  /** Steps into `root` one segment at a time: a key for an object, a position for a list. */
  private[mcp] def at(root: ujson.Value, segments: List[String]): Either[String, ujson.Value] =
    segments.foldLeft(Right(root): Either[String, ujson.Value]): (acc, seg) =>
      acc.flatMap:
        case a: ujson.Arr => seg.toIntOption.flatMap(a.value.lift).toRight(s"no position '$seg'")
        case o: ujson.Obj => o.value.get(seg).toRight(s"no key '$seg'")
        case _            => Left(s"cannot step into a scalar with '$seg'")

  private def sequence[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight(Right(Nil): Either[String, List[A]])((x, acc) =>
      for a <- x; as <- acc yield a :: as
    )
