package com.github.mercurievv.scalasemantic.mcp

import io.circe.Json

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
      val calls = a.asObject.flatMap(_("calls")).flatMap(_.asArray) match
        case Some(arr) => arr.toList
        case None      => McpToolsSupport.error("`calls` must be an array of {tool, args}")
      if calls.size > MaxCalls then
        McpToolsSupport.error(s"at most $MaxCalls calls per batch (got ${calls.size})")
      val results =
        calls.foldLeft(Vector.empty[Json])((done, c) => done :+ runOne(byName, c, done))
      J.obj("results" -> J.arrOf(results))
    }

  private def runOne(
      byName: Map[String, Tool],
      call: Json,
      done: Vector[Json]
  ): Json =
    val name = call.asObject.flatMap(_("tool")).flatMap(_.asString).getOrElse("")
    val outcome: Either[String, Json] =
      for
        tool <-
          if name == "batch" then Left("batch cannot call batch")
          else byName.get(name).toRight(s"unknown tool '$name'")
        args <- resolveRefs(call.asObject.flatMap(_("args")).getOrElse(J.obj()), done)
        result <- Try(
          ResultShaping.shape(ResultShaping.specOf(tool, args), tool.run(args))
        ).toEither.left
          .map(t => Option(t.getMessage).getOrElse(t.getClass.getName))
      yield result
    outcome.fold(
      e => J.obj("tool" -> name, "ok" -> false, "error" -> e),
      r => J.obj("tool" -> name, "ok" -> true, "result" -> r)
    )

  // ---- references --------------------------------------------------------------------------

  /** Replaces every `{"$ref": path}` in `v` with the value `path` addresses in `done`. */
  private[mcp] def resolveRefs(
      v: Json,
      done: Vector[Json]
  ): Either[String, Json] =
    v.fold(
      Right(v),
      _ => Right(v),
      _ => Right(v),
      _ => Right(v),
      items => sequence(items.toList.map(resolveRefs(_, done))).map(J.arrOf(_)),
      o =>
        o.toList match
          case ("$ref", ref) :: Nil =>
            ref.asString.toRight("$ref must be a string").flatMap(resolvePath(_, done))
          case fields =>
            sequence(fields.map((k, x) => resolveRefs(x, done).map(k -> _)))
              .map(J.objOf(_))
    )

  /** `"2.symbols.0.symbol"`: the result of call 2, then key `symbols`, position 0, key `symbol`. */
  private[mcp] def resolvePath(
      path: String,
      done: Vector[Json]
  ): Either[String, Json] =
    path.split('.').toList match
      case head :: rest =>
        for
          idx <- head.toIntOption
            .filter(i => i >= 0 && i < done.size)
            .toRight(s"$$ref '$path': no earlier call '$head' (have ${done.size})")
          entry <- done.lift(idx).toRight(s"$$ref '$path': no earlier call")
          result <-
            if entry.asObject.flatMap(_("ok")).flatMap(_.asBoolean).contains(true) then
              entry.asObject.flatMap(_("result")).toRight(s"$$ref '$path': call $idx has no result")
            else Left(s"$$ref '$path': call $idx failed")
          value <- at(result, rest).left.map(e => s"$$ref '$path': $e")
        yield value
      case Nil => Left("empty $ref")

  /** Steps into `root` one segment at a time: a key for an object, a position for a list. */
  private[mcp] def at(root: Json, segments: List[String]): Either[String, Json] =
    segments.foldLeft(Right(root): Either[String, Json]): (acc, seg) =>
      acc.flatMap: j =>
        j.fold(
          Left(s"cannot step into a scalar with '$seg'"),
          _ => Left(s"cannot step into a scalar with '$seg'"),
          _ => Left(s"cannot step into a scalar with '$seg'"),
          _ => Left(s"cannot step into a scalar with '$seg'"),
          items => seg.toIntOption.flatMap(items.lift).toRight(s"no position '$seg'"),
          o => o(seg).toRight(s"no key '$seg'")
        )

  private def sequence[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight(Right(Nil): Either[String, List[A]])((x, acc) =>
      for a <- x; as <- acc yield a :: as
    )
