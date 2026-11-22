package com.github.mercurievv.scalasemantic.mcp

/** Minimal JSON Schema checker for the subset `OutputSchema` emits (object/array/scalars/`oneOf`).
  * Flags undeclared keys so a drift between the encoder and the declared schema is loud — except
  * keys a `oneOf` node declares beside its alternatives (the wrapper hints).
  */
object SchemaConformance:

  def violations(s: ujson.Value, v: ujson.Value, at: String = "$"): List[String] =
    check(s, v, at, Set.empty)

  private def check(s: ujson.Value, v: ujson.Value, at: String, extra: Set[String]): List[String] =
    s.obj.get("oneOf") match
      case Some(alts) =>
        val shared = s.obj.get("properties").map(_.obj.keySet.toSet).getOrElse(Set.empty[String])
        val perAlt = alts.arr.toList.map(alt => check(alt, v, at, shared))
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
                      case Some(ps)                  => check(ps, x, s"$at.$k", Set.empty)
                      case None if extra.contains(k) => Nil
                      case None                      => List(s"$at: undeclared '$k'")
              case _ => List(s"$at: expected object")
          case Some("array") =>
            v match
              case a: ujson.Arr =>
                a.value.toList.zipWithIndex.flatMap((x, i) =>
                  check(s("items"), x, s"$at[$i]", Set.empty)
                )
              case _ => List(s"$at: expected array")
          case Some("string")  => if v.strOpt.isDefined then Nil else List(s"$at: expected string")
          case Some("boolean") =>
            if v.boolOpt.isDefined then Nil else List(s"$at: expected boolean")
          case Some("integer") =>
            if v.numOpt.exists(d => d == d.toLong) then Nil else List(s"$at: expected integer")
          case Some("number") => if v.numOpt.isDefined then Nil else List(s"$at: expected number")
          case other          => List(s"$at: unsupported schema type $other")
