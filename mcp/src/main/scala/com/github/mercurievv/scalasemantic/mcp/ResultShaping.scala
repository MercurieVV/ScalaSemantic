package com.github.mercurievv.scalasemantic.mcp

/** Per-call shaping of any tool's result, for callers that only need part of a large answer.
  *
  * Three universal arguments, documented once in the server instructions (not repeated in every
  * tool's `inputSchema`, which would cost tokens in `tools/list`):
  *   - `fields`: top-level keys to keep; `list.key` projects the items of a list. Control keys
  *     (`found`, `candidates`, `note`, wrapper hints) always survive, so a miss stays a miss.
  *   - `countOnly`: every top-level list is replaced by its size in `counts`.
  *   - `limit`: every top-level list is cut to this many items; the original sizes of the lists
  *     that were cut go in `truncated`. Not applied to a tool that has its own `limit` (it pages
  *     with `nextOffset`, which a second cut would corrupt).
  *
  * Order: `fields`, then `countOnly`, else `limit`.
  */
object ResultShaping:

  final case class Spec(limit: Option[Int], countOnly: Boolean, fields: Option[List[String]]):
    def isNoop: Boolean = limit.isEmpty && !countOnly && fields.isEmpty

  private type Fields = List[(String, ujson.Value)]

  /** Keys with control meaning: they survive any `fields` projection. */
  val ControlKeys: Set[String] = Set(
    "found",
    "candidates",
    "note",
    "indexEmptyHint",
    "coverageHint",
    "pcFallback",
    "pcFallbackHint"
  )

  def specOf(tool: Tool, args: ujson.Value): Spec =
    val hasOwnLimit = tool.inputSchema.obj.get("properties").exists(_.obj.contains("limit"))
    Spec(
      limit =
        if hasOwnLimit then None
        else args.obj.get("limit").flatMap(_.numOpt).map(_.toInt).filter(_ > 0),
      countOnly = args.obj.get("countOnly").flatMap(_.boolOpt).contains(true),
      fields = args.obj
        .get("fields")
        .collect { case a: ujson.Arr => a.value.toList.flatMap(_.strOpt) }
        .filter(_.nonEmpty)
    )

  def shape(spec: Spec, result: ujson.Value): ujson.Value = result match
    case o: ujson.Obj if !spec.isNoop =>
      val projected = spec.fields.fold(o.value.toList)(project(o.value.toList, _))
      ujson.Obj.from(
        if spec.countOnly then countOnly(projected)
        else spec.limit.fold(projected)(limitTo(projected, _))
      )
    case other => other

  // ---- fields ------------------------------------------------------------------------------

  private def project(fields: Fields, paths: List[String]): Fields =
    val parsed = paths.map: p =>
      p.indexOf('.') match
        case -1 => (p, Option.empty[String])
        case i  => (p.take(i), Some(p.drop(i + 1)))
    fields.flatMap: (k, v) =>
      val mine = parsed.filter(_._1 == k)
      if ControlKeys(k) then List(k -> v)
      else if mine.isEmpty then Nil
      else if mine.exists(_._2.isEmpty) then List(k -> v)
      else List(k -> projectValue(v, mine.flatMap(_._2)))

  private def projectValue(v: ujson.Value, sub: List[String]): ujson.Value = v match
    case a: ujson.Arr => ujson.Arr.from(a.value.toList.map(projectValue(_, sub)))
    case o: ujson.Obj => ujson.Obj.from(project(o.value.toList, sub))
    case other        => other

  // ---- countOnly / limit -------------------------------------------------------------------

  private def countOnly(fields: Fields): Fields =
    val counts = fields.collect { case (k, a: ujson.Arr) =>
      k -> (ujson.Num(a.value.size): ujson.Value)
    }
    val rest = fields.filter:
      case (_, _: ujson.Arr) => false
      case _                 => true
    rest ++ withKey("counts", counts)

  private def limitTo(fields: Fields, n: Int): Fields =
    val truncated = fields.collect:
      case (k, a: ujson.Arr) if a.value.size > n => k -> (ujson.Num(a.value.size): ujson.Value)
    val capped = fields.map:
      case (k, a: ujson.Arr) if a.value.size > n =>
        k -> (ujson.Arr.from(a.value.toList.take(n)): ujson.Value)
      case kv => kv
    capped ++ withKey("truncated", truncated)

  private def withKey(key: String, entries: Fields): Fields =
    if entries.isEmpty then Nil else List(key -> (ujson.Obj.from(entries): ujson.Value))
