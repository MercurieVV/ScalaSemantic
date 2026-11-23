package com.github.mercurievv.scalasemantic.mcp

/** Minimal JSON Schema checker for the subset `OutputSchema` emits (object/array/scalars/`oneOf`,
  * and `$ref` into the root's `$defs` for recursive types). Flags undeclared keys so a drift
  * between the encoder and the declared schema is loud — except keys a `oneOf` node declares beside
  * its alternatives (the wrapper hints).
  */
object SchemaConformance:

  def violations(s: ujson.Value, v: ujson.Value, at: String = "$"): List[String] =
    check(s, s, v, at, Set.empty)

  /** Follows `{"$ref": "#/$defs/Name"}` into the root schema. */
  private def resolve(root: ujson.Value, s: ujson.Value): ujson.Value =
    s.obj.get("$ref") match
      case Some(r) =>
        val name = r.str.stripPrefix("#/$defs/")
        root.obj
          .get("$defs")
          .flatMap(_.obj.get(name))
          .getOrElse(ujson.Obj("type" -> "unresolved-ref"))
      case None => s

  /** Adds the `["T", "null"]` union and the bare `{"type": "null"}` to what [[checkOne]] covers. */
  private def check(
      root: ujson.Value,
      node: ujson.Value,
      v: ujson.Value,
      at: String,
      extra: Set[String]
  ): List[String] =
    val s = resolve(root, node)
    s.obj.get("type") match
      case Some(ts: ujson.Arr) =>
        val names = ts.arr.toList.map(_.str)
        if v == ujson.Null && names.contains("null") then Nil
        else
          val tries = names
            .filterNot(_ == "null")
            .map(n =>
              check(
                root,
                ujson.Obj.from(s.obj.toList.map {
                  case ("type", _) => "type" -> ujson.Str(n)
                  case other       => other
                }),
                v,
                at,
                extra
              )
            )
          if tries.exists(_.isEmpty) then Nil else List(s"$at: matches no type of $names")
      case Some(ujson.Str("null")) =>
        if v == ujson.Null then Nil else List(s"$at: expected null")
      case _ => checkOne(root, node, v, at, extra)

  private def checkOne(
      root: ujson.Value,
      node: ujson.Value,
      v: ujson.Value,
      at: String,
      extra: Set[String]
  ): List[String] =
    val s = resolve(root, node)
    s.obj.get("oneOf") match
      case Some(alts) =>
        val shared = s.obj.get("properties").map(_.obj.keySet.toSet).getOrElse(Set.empty[String])
        val perAlt = alts.arr.toList.map(alt => check(root, alt, v, at, shared))
        if perAlt.exists(_.isEmpty) then Nil else List(s"$at: matches no oneOf branch: $perAlt")
      case None =>
        s.obj.get("type").map(_.str) match
          case Some("object") =>
            v match
              case o: ujson.Obj =>
                val props =
                  s.obj.get("properties").map(_.obj.toMap).getOrElse(Map.empty[String, ujson.Value])
                val required = s.obj.get("required").toList.flatMap(_.arr.toList.map(_.str))
                required.filterNot(o.value.contains).map(k => s"$at: missing '$k'") ++
                  o.value.toList.flatMap: (k, x) =>
                    props.get(k) match
                      case Some(ps)                  => check(root, ps, x, s"$at.$k", Set.empty)
                      case None if extra.contains(k) => Nil
                      case None                      =>
                        s.obj.get("additionalProperties") match
                          case Some(vs) => check(root, vs, x, s"$at.$k", Set.empty)
                          case None     => List(s"$at: undeclared '$k'")
              case _ => List(s"$at: expected object")
          case Some("array") =>
            v match
              case a: ujson.Arr =>
                a.value.toList.zipWithIndex.flatMap((x, i) =>
                  check(root, s("items"), x, s"$at[$i]", Set.empty)
                )
              case _ => List(s"$at: expected array")
          case Some("string")  => if v.strOpt.isDefined then Nil else List(s"$at: expected string")
          case Some("boolean") =>
            if v.boolOpt.isDefined then Nil else List(s"$at: expected boolean")
          case Some("integer") =>
            if v.numOpt.exists(d => d == d.toLong) then Nil else List(s"$at: expected integer")
          case Some("number") => if v.numOpt.isDefined then Nil else List(s"$at: expected number")
          case other          => List(s"$at: unsupported schema type $other")
