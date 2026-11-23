package com.github.mercurievv.scalasemantic.mcp

import io.circe.Json

/** Minimal JSON Schema checker for the subset `OutputSchema` emits (object/array/scalars/`oneOf`,
  * `$ref` into the root's `$defs` for recursive types, `["T","null"]` unions, and
  * `additionalProperties`). Flags undeclared keys so a drift between the encoder and the declared
  * schema is loud — except keys a `oneOf` node declares beside its alternatives (the wrapper
  * hints).
  */
object SchemaConformance:

  def violations(s: Json, v: Json, at: String = "$"): List[String] =
    check(s, s, v, at, Set.empty)

  /** Follows `{"$ref": "#/$defs/Name"}` into the root schema. */
  private def resolve(root: Json, s: Json): Json =
    s.asObject.flatMap(_("$ref")).flatMap(_.asString) match
      case Some(r) =>
        val name = r.stripPrefix("#/$defs/")
        root.asObject
          .flatMap(_("$defs"))
          .flatMap(_.asObject)
          .flatMap(_(name))
          .getOrElse(Json.obj("type" -> Json.fromString("unresolved-ref")))
      case None => s

  /** Adds the `["T", "null"]` union and the bare `{"type": "null"}` to what [[checkOne]] covers. */
  private def check(
      root: Json,
      node: Json,
      v: Json,
      at: String,
      extra: Set[String]
  ): List[String] =
    val s = resolve(root, node)
    val typeField = s.asObject.flatMap(_("type"))
    typeField.flatMap(_.asArray) match
      case Some(ts) =>
        val names = ts.toList.flatMap(_.asString)
        if v.isNull && names.contains("null") then Nil
        else
          val tries = names
            .filterNot(_ == "null")
            .map(n =>
              check(
                root,
                Json.fromJsonObject(
                  s.asObject.getOrElse(io.circe.JsonObject.empty).add("type", Json.fromString(n))
                ),
                v,
                at,
                extra
              )
            )
          if tries.exists(_.isEmpty) then Nil else List(s"$at: matches no type of $names")
      case None if typeField.flatMap(_.asString).contains("null") =>
        if v.isNull then Nil else List(s"$at: expected null")
      case None => checkOne(root, s, v, at, extra)

  private def checkOne(
      root: Json,
      s: Json,
      v: Json,
      at: String,
      extra: Set[String]
  ): List[String] =
    val so = s.asObject.getOrElse(io.circe.JsonObject.empty)
    so("oneOf").flatMap(_.asArray) match
      case Some(alts) =>
        val shared =
          so("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty[String])
        val perAlt = alts.toList.map(alt => check(root, alt, v, at, shared))
        if perAlt.exists(_.isEmpty) then Nil else List(s"$at: matches no oneOf branch: $perAlt")
      case None =>
        so("type").flatMap(_.asString) match
          case Some("object") =>
            v.asObject match
              case Some(o) =>
                val props = so("properties")
                  .flatMap(_.asObject)
                  .map(_.toMap)
                  .getOrElse(Map.empty[String, Json])
                val required =
                  so("required").flatMap(_.asArray).toList.flatMap(_.toList.flatMap(_.asString))
                required.filterNot(o.contains).map(k => s"$at: missing '$k'") ++
                  o.toList.flatMap: (k, x) =>
                    props.get(k) match
                      case Some(ps)                  => check(root, ps, x, s"$at.$k", Set.empty)
                      case None if extra.contains(k) => Nil
                      case None                      =>
                        so("additionalProperties") match
                          case Some(vs) => check(root, vs, x, s"$at.$k", Set.empty)
                          case None     => List(s"$at: undeclared '$k'")
              case None => List(s"$at: expected object")
          case Some("array") =>
            v.asArray match
              case Some(a) =>
                a.toList.zipWithIndex.flatMap((x, i) =>
                  check(root, so("items").getOrElse(Json.obj()), x, s"$at[$i]", Set.empty)
                )
              case None => List(s"$at: expected array")
          case Some("string")  => if v.isString then Nil else List(s"$at: expected string")
          case Some("boolean") => if v.isBoolean then Nil else List(s"$at: expected boolean")
          case Some("integer") =>
            if v.asNumber.exists(n => n.toLong.isDefined) then Nil
            else List(s"$at: expected integer")
          case Some("number") => if v.isNumber then Nil else List(s"$at: expected number")
          case other          => List(s"$at: unsupported schema type $other")
