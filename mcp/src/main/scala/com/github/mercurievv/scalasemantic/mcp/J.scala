package com.github.mercurievv.scalasemantic.mcp

import io.circe.Json
import io.circe.parser

import scala.collection.immutable.ListMap

/** Small ergonomics over circe `Json` for the protocol layer and the tools that build their answer
  * by hand (ADR 0006): construction (`J.obj`, `J.arr`), reads that fail loudly like the JSON
  * library this layer used to be built on (`j("key")`, `j.str`, `j.arr`), and printing/parsing.
  *
  * Numbers: a whole `Double` is written as an integer (`2`, not `2.0`) so the wire stays what it
  * was byte for byte.
  */
object J:

  /** Anything that can sit in a hand-built object or array. */
  type V = Json | String | Int | Long | Double | Boolean

  val Null: Json = Json.Null

  private def toJson(v: V): Json = v match
    case j: Json    => j
    case s: String  => Json.fromString(s)
    case i: Int     => Json.fromInt(i)
    case l: Long    => Json.fromLong(l)
    case d: Double  => num(d)
    case b: Boolean => Json.fromBoolean(b)

  def obj(fields: (String, V)*): Json = Json.fromFields(fields.map((k, v) => k -> toJson(v)))

  /** An object from already-built fields, in order. */
  def objOf(fields: Iterable[(String, Json)]): Json = Json.fromFields(fields)

  def arr(items: V*): Json = Json.fromValues(items.map(toJson))

  def arrOf(items: Iterable[Json]): Json = Json.fromValues(items)

  def str(s: String): Json = Json.fromString(s)

  def bool(b: Boolean): Json = Json.fromBoolean(b)

  def num(i: Int): Json = Json.fromInt(i)

  def num(l: Long): Json = Json.fromLong(l)

  def num(d: Double): Json =
    if d.isWhole && math.abs(d) < 1e15 then Json.fromLong(d.toLong) else Json.fromDoubleOrNull(d)

  def write(j: Json): String = j.noSpaces

  /** Two-space indent, `"key": value` (no space before the colon). */
  def write(j: Json, indent: Int): String =
    if indent <= 0 then j.noSpaces else pretty(j, " " * indent, 0)

  // Hand-rolled so empty containers stay `{}` / `[]` (circe's indented printer opens a blank line).
  private def pretty(j: Json, unit: String, level: Int): String =
    val inner = unit * (level + 1)
    val outer = unit * level
    j.fold(
      "null",
      _ => j.noSpaces,
      _ => j.noSpaces,
      _ => j.noSpaces,
      items =>
        if items.isEmpty then "[]"
        else
          items.map(i => inner + pretty(i, unit, level + 1)).mkString("[\n", ",\n", s"\n$outer]")
      ,
      o =>
        if o.isEmpty then "{}"
        else
          o.toList
            .map((k, v) => s"$inner${Json.fromString(k).noSpaces}: ${pretty(v, unit, level + 1)}")
            .mkString("{\n", ",\n", s"\n$outer}")
    )

  def read(s: String): Json = parser.parse(s).fold(e => sys.error(e.getMessage), identity)

end J

/** Read helpers on `Json` that fail loudly (`j("key")`, `j.str`, `j.arr`); import `JOps.*`. */
object JOps:

  private def bad(what: String, j: Json): Nothing =
    sys.error(s"expected $what, got: ${j.noSpaces.take(80)}")

  extension (j: Json)
    def isNullValue: Boolean = j.isNull

    /** Two-space-indented text, `"key": value`. */
    def pretty: String = J.write(j, 2)
    def strOpt: Option[String] = j.asString
    def str: String = j.asString.getOrElse(bad("a string", j))
    def boolOpt: Option[Boolean] = j.asBoolean
    def bool: Boolean = j.asBoolean.getOrElse(bad("a boolean", j))
    def numOpt: Option[Double] = j.asNumber.map(_.toDouble)
    def num: Double = j.asNumber.map(_.toDouble).getOrElse(bad("a number", j))
    def arrOpt: Option[List[Json]] = j.asArray.map(_.toList)
    def arr: List[Json] = j.asArray.map(_.toList).getOrElse(bad("an array", j))
    def objOpt: Option[ListMap[String, Json]] = j.asObject.map(o => ListMap.from(o.toList))
    def obj: ListMap[String, Json] = objOpt.getOrElse(bad("an object", j))
    def get(key: String): Option[Json] = j.asObject.flatMap(_(key))
    def apply(key: String): Json = get(key).getOrElse(bad(s"key '$key'", j))
    def apply(i: Int): Json = j.asArray.flatMap(_.lift(i)).getOrElse(bad(s"position $i", j))

    /** This object with `extra` fields appended (a later duplicate replaces an earlier key). */
    def withFields(extra: Iterable[(String, Json)]): Json =
      Json.fromFields(ListMap.from(j.obj.toList ++ extra))
end JOps
