package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.mcp.JOps.*
import io.circe.Json
import io.circe.JsonObject
import sttp.tapir.Schema
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType

/** Compact JSON Schema (MCP `outputSchema`) from a tapir `Schema` tree.
  *
  * tapir's own apispec output is OpenAPI-shaped (`$ref` components, titles, nullable wrappers) and
  * token-heavy; `outputSchema` is fetched by code that parses results, so this walks the derived
  * tree and emits only what a client or LLM needs: inlined, self-contained, `description` where the
  * type carries one.
  *
  * Rule: a field is `required` unless its type is an `Option`. Collections are NOT optional — a
  * result that omits an empty list models it as `Option[List[_]]`.
  *
  * Only recursive types (a tree whose `children` hold the same type) use `$ref`: each is emitted
  * once under `$defs` and referenced as `#/$defs/<Name>`; everything else stays inline.
  */
object OutputSchema:

  /** Fields the server wrappers (`McpTools.all`) append to any object result. */
  private val WrapperHints: List[(String, Json)] = List(
    "indexEmptyHint" -> described("string", "present when the SemanticDB index is empty"),
    "coverageHint" -> described(
      "string",
      "present on an empty result when some sources are not indexed"
    )
  )

  def of[A](using s: Schema[A]): Json = withDefs(s, withHints(emit(s, recursive(s))))

  private def described(tpe: String, desc: String): Json =
    J.obj("type" -> tpe, "description" -> desc)

  private def withHints(schema: Json): Json = schema.asObject match
    case Some(o) if o("type").contains(J.str("object")) =>
      val props = o("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
      val merged =
        WrapperHints.foldLeft(props)((p, kv) =>
          if p.contains(kv._1) then p else p.add(kv._1, kv._2)
        )
      Json.fromJsonObject(o.add("properties", Json.fromJsonObject(merged)))
    case Some(o) if o.contains("oneOf") =>
      // Documented once beside the alternatives (JSON Schema allows undeclared keys), not per branch.
      Json.fromJsonObject(o.add("properties", J.objOf(WrapperHints)))
    case _ => schema

  // ---- recursion ---------------------------------------------------------------------------

  private def children(s: Schema[?]): List[Schema[?]] = s.schemaType match
    case SchemaType.SProduct(fields)      => fields.map(_.schema)
    case SchemaType.SArray(el)            => List(el)
    case SchemaType.SOption(el)           => List(el)
    case SchemaType.SCoproduct(subs, _)   => subs
    case o: SchemaType.SOpenProduct[?, ?] => List(o.valueSchema)
    case _                                => Nil

  /** The schema and every schema below it (a reference to a recursive type is a leaf). */
  private def walk(s: Schema[?]): List[Schema[?]] = s :: children(s).flatMap(walk)

  /** Names of types that are referenced from within themselves. */
  private def recursive(root: Schema[?]): Set[SName] =
    walk(root)
      .flatMap(_.schemaType match
        case SchemaType.SRef(name) => List(name)
        case _                     => Nil)
      .toSet

  private def refName(n: SName): String = n.fullName.split('.').last.filter(_.isLetterOrDigit)

  private def ref(n: SName): Json = J.obj("$ref" -> s"#/$$defs/${refName(n)}")

  private def withDefs(root: Schema[?], schema: Json): Json =
    val rec = recursive(root)
    if rec.isEmpty then schema
    else
      val defs = walk(root)
        .flatMap(s => s.name.filter(rec.contains).filter(_ => !isRef(s)).map(_ -> s))
        .distinctBy(_._1)
        .map((n, s) => refName(n) -> emit(s, rec, asDefinition = true))
      schema.asObject.fold(schema)(o => Json.fromJsonObject(o.add("$defs", J.objOf(defs))))

  private def isRef(s: Schema[?]): Boolean = s.schemaType match
    case _: SchemaType.SRef[?] => true
    case _                     => false

  // ---- emission ----------------------------------------------------------------------------

  private def unwrap(s: Schema[?]): Schema[?] = s.schemaType match
    case SchemaType.SOption(inner) => unwrap(inner)
    case _                         => s

  def emit(s: Schema[?]): Json = emit(s, Set.empty[SName])

  private def emit(s: Schema[?], rec: Set[SName], asDefinition: Boolean = false): Json =
    s.schemaType match
      case SchemaType.SRef(name)                             => ref(name)
      case _ if !asDefinition && s.name.exists(rec.contains) =>
        s.name.fold(J.obj(): Json)(ref)
      case _ =>
        val base = s.schemaType match
          case SchemaType.SProduct(fields) =>
            val props = J.objOf(fields.map(f => f.name.name -> emit(f.schema, rec)))
            val req = fields
              .filterNot(f => isOption(f.schema))
              .map(f => J.str(f.name.name): Json)
            val o = J.obj("type" -> "object", "properties" -> props)
            if req.nonEmpty then o.withFields(List("required" -> J.arrOf(req))) else o
          case SchemaType.SArray(el)          => J.obj("type" -> "array", "items" -> emit(el, rec))
          case SchemaType.SOption(el)         => emit(el, rec)
          case SchemaType.SCoproduct(subs, _) =>
            J.obj("oneOf" -> J.arrOf(subs.map(emit(_, rec))))
          case _: SchemaType.SString[?]         => J.obj("type" -> "string")
          case _: SchemaType.SInteger[?]        => J.obj("type" -> "integer")
          case _: SchemaType.SNumber[?]         => J.obj("type" -> "number")
          case _: SchemaType.SBoolean[?]        => J.obj("type" -> "boolean")
          case o: SchemaType.SOpenProduct[?, ?] =>
            J.obj("type" -> "object", "additionalProperties" -> emit(o.valueSchema, rec))
          case _ => J.obj()
        // An Option's own description may sit on the wrapper or on the inner schema; prefer the inner.
        val desc = unwrap(s).description.orElse(s.description)
        desc.fold(base): d =>
          if base.obj.contains("description") then base
          else base.withFields(List("description" -> J.str(d)))

  private def isOption(s: Schema[?]): Boolean = s.schemaType match
    case _: SchemaType.SOption[?, ?] => true
    case _                           => false
