package com.github.mercurievv.scalasemantic.mcp

import sttp.tapir.Schema
import sttp.tapir.SchemaType

/** Compact JSON Schema (MCP `outputSchema`) from a tapir `Schema` tree.
  *
  * tapir's own apispec output is OpenAPI-shaped (`$ref` components, titles, nullable wrappers) and
  * token-heavy; `outputSchema` rides along in every `tools/list`, so this walks the derived tree
  * and emits only what a client or LLM needs: inlined, self-contained, `description` where the type
  * carries one.
  *
  * Rule: a field is `required` unless its type is an `Option`. Collections are NOT optional — a
  * result that omits an empty list models it as `Option[List[_]]`.
  */
object OutputSchema:

  /** Fields the server wrappers (`McpTools.all`) append to any object result. */
  private val WrapperHints: List[(String, ujson.Value)] = List(
    "indexEmptyHint" -> described("string", "present when the SemanticDB index is empty"),
    "coverageHint" -> described(
      "string",
      "present on an empty result when some sources are not indexed"
    )
  )

  def of[A](using s: Schema[A]): ujson.Value = withHints(emit(s))

  private def described(tpe: String, desc: String): ujson.Value =
    ujson.Obj("type" -> tpe, "description" -> desc)

  private def withHints(schema: ujson.Value): ujson.Value = schema match
    case o: ujson.Obj if o.value.get("type").contains(ujson.Str("object")) =>
      val props =
        o.value.get("properties").collect { case p: ujson.Obj => p }.getOrElse(ujson.Obj())
      WrapperHints.foreach((k, v) => props.value.getOrElseUpdate(k, v))
      o.value("properties") = props
      o
    case other => other

  private def unwrap(s: Schema[?]): Schema[?] = s.schemaType match
    case SchemaType.SOption(inner) => unwrap(inner)
    case _                         => s

  def emit(s: Schema[?]): ujson.Value =
    val base = s.schemaType match
      case SchemaType.SProduct(fields) =>
        val props = ujson.Obj.from(fields.map(f => f.name.name -> emit(f.schema)))
        val req =
          fields.filterNot(f => isOption(f.schema)).map(f => ujson.Str(f.name.name): ujson.Value)
        val o = ujson.Obj("type" -> "object", "properties" -> props)
        if req.nonEmpty then o("required") = ujson.Arr.from(req)
        o
      case SchemaType.SArray(el)            => ujson.Obj("type" -> "array", "items" -> emit(el))
      case SchemaType.SOption(el)           => emit(el)
      case SchemaType.SCoproduct(subs, _)   => ujson.Obj("oneOf" -> ujson.Arr.from(subs.map(emit)))
      case _: SchemaType.SString[?]         => ujson.Obj("type" -> "string")
      case _: SchemaType.SInteger[?]        => ujson.Obj("type" -> "integer")
      case _: SchemaType.SNumber[?]         => ujson.Obj("type" -> "number")
      case _: SchemaType.SBoolean[?]        => ujson.Obj("type" -> "boolean")
      case o: SchemaType.SOpenProduct[?, ?] =>
        ujson.Obj("type" -> "object", "additionalProperties" -> emit(o.valueSchema))
      case _ => ujson.Obj()
    // An Option's own description may sit on the wrapper or on the inner schema; prefer the inner.
    val desc = unwrap(s).description.orElse(s.description)
    desc.foreach(d => base.obj.getOrElseUpdate("description", ujson.Str(d)))
    base

  private def isOption(s: Schema[?]): Boolean = s.schemaType match
    case _: SchemaType.SOption[?, ?] => true
    case _                           => false
