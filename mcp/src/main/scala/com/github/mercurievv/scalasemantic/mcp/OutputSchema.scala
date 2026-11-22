package com.github.mercurievv.scalasemantic.mcp

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
  private val WrapperHints: List[(String, ujson.Value)] = List(
    "indexEmptyHint" -> described("string", "present when the SemanticDB index is empty"),
    "coverageHint" -> described(
      "string",
      "present on an empty result when some sources are not indexed"
    )
  )

  def of[A](using s: Schema[A]): ujson.Value = withDefs(s, withHints(emit(s, recursive(s))))

  private def described(tpe: String, desc: String): ujson.Value =
    ujson.Obj("type" -> tpe, "description" -> desc)

  private def withHints(schema: ujson.Value): ujson.Value = schema match
    case o: ujson.Obj if o.value.get("type").contains(ujson.Str("object")) =>
      val props =
        o.value.get("properties").collect { case p: ujson.Obj => p }.getOrElse(ujson.Obj())
      WrapperHints.foreach((k, v) => props.value.getOrElseUpdate(k, v))
      o.value("properties") = props
      o
    case o: ujson.Obj if o.value.contains("oneOf") =>
      // Documented once beside the alternatives (JSON Schema allows undeclared keys), not per branch.
      o.value("properties") = ujson.Obj.from(WrapperHints)
      o
    case other => other

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

  private def ref(n: SName): ujson.Value = ujson.Obj("$ref" -> s"#/$$defs/${refName(n)}")

  private def withDefs(root: Schema[?], schema: ujson.Value): ujson.Value =
    val rec = recursive(root)
    if rec.isEmpty then schema
    else
      val defs = walk(root)
        .flatMap(s => s.name.filter(rec.contains).filter(_ => !isRef(s)).map(_ -> s))
        .distinctBy(_._1)
        .map((n, s) => refName(n) -> emit(s, rec, asDefinition = true))
      schema match
        case o: ujson.Obj =>
          o.value("$defs") = ujson.Obj.from(defs)
          o
        case other => other

  private def isRef(s: Schema[?]): Boolean = s.schemaType match
    case _: SchemaType.SRef[?] => true
    case _                     => false

  // ---- emission ----------------------------------------------------------------------------

  private def unwrap(s: Schema[?]): Schema[?] = s.schemaType match
    case SchemaType.SOption(inner) => unwrap(inner)
    case _                         => s

  def emit(s: Schema[?]): ujson.Value = emit(s, Set.empty[SName])

  private def emit(s: Schema[?], rec: Set[SName], asDefinition: Boolean = false): ujson.Value =
    s.schemaType match
      case SchemaType.SRef(name)                             => ref(name)
      case _ if !asDefinition && s.name.exists(rec.contains) =>
        s.name.fold(ujson.Obj(): ujson.Value)(ref)
      case _ =>
        val base = s.schemaType match
          case SchemaType.SProduct(fields) =>
            val props = ujson.Obj.from(fields.map(f => f.name.name -> emit(f.schema, rec)))
            val req = fields
              .filterNot(f => isOption(f.schema))
              .map(f => ujson.Str(f.name.name): ujson.Value)
            val o = ujson.Obj("type" -> "object", "properties" -> props)
            if req.nonEmpty then o("required") = ujson.Arr.from(req)
            o
          case SchemaType.SArray(el)  => ujson.Obj("type" -> "array", "items" -> emit(el, rec))
          case SchemaType.SOption(el) => emit(el, rec)
          case SchemaType.SCoproduct(subs, _) =>
            ujson.Obj("oneOf" -> ujson.Arr.from(subs.map(emit(_, rec))))
          case _: SchemaType.SString[?]         => ujson.Obj("type" -> "string")
          case _: SchemaType.SInteger[?]        => ujson.Obj("type" -> "integer")
          case _: SchemaType.SNumber[?]         => ujson.Obj("type" -> "number")
          case _: SchemaType.SBoolean[?]        => ujson.Obj("type" -> "boolean")
          case o: SchemaType.SOpenProduct[?, ?] =>
            ujson.Obj("type" -> "object", "additionalProperties" -> emit(o.valueSchema, rec))
          case _ => ujson.Obj()
        // An Option's own description may sit on the wrapper or on the inner schema; prefer the inner.
        val desc = unwrap(s).description.orElse(s.description)
        desc.foreach(d => base.obj.getOrElseUpdate("description", ujson.Str(d)))
        base

  private def isOption(s: Schema[?]): Boolean = s.schemaType match
    case _: SchemaType.SOption[?, ?] => true
    case _                           => false
