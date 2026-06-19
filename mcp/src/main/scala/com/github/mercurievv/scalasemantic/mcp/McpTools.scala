package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.model.*

/** The nine analysis tools exposed over MCP, with token-lean JSON rendering.
  *
  * Conventions: a symbol is a SemanticDB symbol string (e.g. `pkg/Type#method().`). Results stay
  * minimal — pass `"detailed": true` to expand the structured breakdown of a result.
  */
object McpTools:

  def all(az: Analyzer): List[Tool] = List(
    tool(
      "find_symbol",
      "START HERE INSTEAD OF grep when you have a plain name. Resolve a plain or partial name " +
        "(e.g. 'Animal', 'show') to the SemanticDB symbol " +
        "strings the other tools require. Ranked exact > prefix > substring. Narrow with `exact` " +
        "(name equality only), `kind` (e.g. TRAIT, CLASS, METHOD, OBJECT) and `pathFilter` (glob on " +
        "the symbol's definition uri) to cut tokens.",
      List(
        ("query", "string", "simple or partial name to search for"),
        ("limit", "integer", "max results (default 50)"),
        ("exact", "boolean", "match the display name exactly, case-insensitive (default false)"),
        ("kind", "string", "keep only this SymbolInformation kind, e.g. TRAIT, CLASS, METHOD"),
        ("pathFilter", "string", "glob on the symbol's definition uri; `*` matches any chars")
      ),
      List("query")
    ) { a =>
      val q = argStr(a, "query")
      val results = az.findSymbol(
        q,
        argInt(a, "limit", 50),
        argBool(a, "exact", false),
        a.obj.get("kind").map(_.str),
        a.obj.get("pathFilter").map(_.str)
      )
      jobj(
        Some("query" -> ujson.Str(q)),
        Some("count" -> ujson.Num(results.size)),
        Some(
          "symbols" -> ujson.Arr.from(
            results.map(r =>
              jobj(
                Some("symbol" -> ujson.Str(r.symbol)),
                Some("name" -> ujson.Str(r.displayName)),
                Some("kind" -> ujson.Str(r.kind))
              )
            )
          )
        )
      )
    },
    tool(
      "find_usages",
      "USE INSTEAD OF grep to find where a symbol is used. All references to a symbol across the " +
        "codebase, split into definitions and references (paged). " +
        "Use `pathFilter` (glob, e.g. `core/*` or `*compat*`) to scope to files and `include` " +
        "(subset of [\"definitions\",\"references\"]) to drop sections — both cut tokens. " +
        "`referenceCount` is always returned.",
      List(
        ("symbol", "string", "SemanticDB symbol to search for"),
        ("limit", "integer", "max references to return (default 100)"),
        ("offset", "integer", "reference offset for paging (default 0)"),
        ("pathFilter", "string", "glob on document uri; `*` matches any chars (substring match)"),
        (
          "include",
          "array",
          "sections to return: any of \"definitions\", \"references\" (default both)"
        )
      ),
      List("symbol")
    ) { a =>
      val symbol = argStr(a, "symbol")
      val limit = argInt(a, "limit", 100)
      val offset = argInt(a, "offset", 0)
      val want = includeWant(a)
      val u = az.findUsages(symbol, a.obj.get("pathFilter").map(_.str))
      val page = u.references.slice(offset, offset + limit)
      jobj(
        Some("symbol" -> ujson.Str(symbol)),
        Some("name" -> ujson.Str(u.displayName)),
        opt(
          want("definitions") && u.definitions.nonEmpty,
          "definitions" -> strs(u.definitions.map(loc))
        ),
        Some("referenceCount" -> ujson.Num(u.references.size)),
        opt(want("references") && page.nonEmpty, "references" -> strs(page.map(loc))),
        opt(
          want("references") && offset + limit < u.references.size,
          "nextOffset" -> ujson.Num(offset + limit)
        )
      )
    },
    tool(
      "method_signature",
      "USE INSTEAD OF reading source to learn a method's shape. Full method signature including " +
        "type parameters and implicit/using parameter lists.",
      List(
        ("symbol", "string", "method symbol"),
        ("detailed", "boolean", "include structured parameter breakdown (default false)")
      ),
      List("symbol")
    ) { a =>
      val symbol = argStr(a, "symbol")
      az.methodSignature(symbol) match
        case None => notFound(symbol)
        case Some(m) =>
          if !argBool(a, "detailed", false) then
            jobj(Some("symbol" -> ujson.Str(symbol)), Some("signature" -> ujson.Str(m.rendered)))
          else
            val lists = m.parameterLists.map { pl =>
              jobj(
                Some("implicit" -> ujson.Bool(pl.isImplicit)),
                Some(
                  "params" -> ujson.Arr.from(
                    pl.parameters.map(p =>
                      jobj(
                        Some("name" -> ujson.Str(p.name)),
                        Some("type" -> ujson.Str(p.tpe)),
                        opt(p.isImplicit, "implicit" -> ujson.Bool(true))
                      )
                    )
                  )
                )
              )
            }
            jobj(
              Some("symbol" -> ujson.Str(symbol)),
              Some("signature" -> ujson.Str(m.rendered)),
              opt(m.typeParameters.nonEmpty, "typeParameters" -> strs(m.typeParameters)),
              opt(lists.nonEmpty, "parameterLists" -> ujson.Arr.from(lists)),
              Some("returnType" -> ujson.Str(m.returnType))
            )
    },
    tool(
      "class_hierarchy",
      "USE INSTEAD OF grep to find subtypes/supertypes/implementers. Parents, transitive " +
        "linearization, and known subtypes of a class/trait. Use `pathFilter` " +
        "(glob on a related type's definition uri) and `include` (subset of [\"parents\"," +
        "\"linearization\",\"knownSubtypes\"]) to cut tokens.",
      List(
        ("symbol", "string", "class or trait symbol"),
        ("detailed", "boolean", "expand related types to {symbol,name,kind} (default false)"),
        ("pathFilter", "string", "glob on a related type's definition uri; `*` matches any chars"),
        (
          "include",
          "array",
          "sections to return: any of \"parents\", \"linearization\", \"knownSubtypes\" (default all)"
        )
      ),
      List("symbol")
    ) { a =>
      val symbol = argStr(a, "symbol")
      val detailed = argBool(a, "detailed", false)
      val want = includeWant(a)
      az.classHierarchy(symbol, a.obj.get("pathFilter").map(_.str)) match
        case None => notFound(symbol)
        case Some(h) =>
          jobj(
            Some("symbol" -> ujson.Str(symbol)),
            Some("name" -> ujson.Str(h.displayName)),
            opt(want("parents") && h.parents.nonEmpty, "parents" -> refs(h.parents, detailed)),
            opt(
              want("linearization") && h.linearization.nonEmpty,
              "linearization" -> refs(h.linearization, detailed)
            ),
            opt(
              want("knownSubtypes") && h.knownSubtypes.nonEmpty,
              "knownSubtypes" -> refs(h.knownSubtypes, detailed)
            )
          )
    },
    tool(
      "find_overloads",
      "USE INSTEAD OF grep to find overloads. All method overloads sharing a name and owner with " +
        "the given method.",
      List(("symbol", "string", "any one overload's symbol")),
      List("symbol")
    ) { a =>
      val o = az.findOverloads(argStr(a, "symbol"))
      jobj(
        Some("name" -> ujson.Str(o.name)),
        Some("overloads" -> strs(o.overloads.map(_.rendered)))
      )
    },
    tool(
      "members",
      "USE INSTEAD OF reading source to list a type's members. Members declared on a type versus " +
        "those inherited from its linearization. Use `pathFilter` " +
        "(glob on a member's definition uri) and `include` (subset of [\"declared\",\"inherited\"]) " +
        "to cut tokens.",
      List(
        ("symbol", "string", "class or trait symbol"),
        ("detailed", "boolean", "include kinds and declaring symbols (default false)"),
        ("pathFilter", "string", "glob on a member's definition uri; `*` matches any chars"),
        (
          "include",
          "array",
          "sections to return: any of \"declared\", \"inherited\" (default both)"
        )
      ),
      List("symbol")
    ) { a =>
      val symbol = argStr(a, "symbol")
      val detailed = argBool(a, "detailed", false)
      val want = includeWant(a)
      az.members(symbol, a.obj.get("pathFilter").map(_.str)) match
        case None => notFound(symbol)
        case Some(m) =>
          val declared =
            if detailed then ujson.Arr.from(m.declared.map(memberJson))
            else strs(m.declared.map(_.displayName))
          val inherited = ujson.Arr.from(m.inherited.map { mi =>
            if detailed then memberJson(mi)
            else
              jobj(
                Some("name" -> ujson.Str(mi.displayName)),
                Some("from" -> ujson.Str(mi.declaredIn.displayName))
              )
          })
          jobj(
            Some("symbol" -> ujson.Str(symbol)),
            Some("name" -> ujson.Str(m.displayName)),
            opt(want("declared") && m.declared.nonEmpty, "declared" -> declared),
            opt(want("inherited") && m.inherited.nonEmpty, "inherited" -> inherited)
          )
    },
    tool(
      "type_at_position",
      "USE INSTEAD OF guessing a type from source. The most specific symbol and its type at a " +
        "0-based position in a document.",
      List(
        ("uri", "string", "document uri as it appears in SemanticDB"),
        ("line", "integer", "0-based line"),
        ("character", "integer", "0-based column")
      ),
      List("uri", "line", "character")
    ) { a =>
      az.typeAtPosition(argStr(a, "uri"), argInt(a, "line", 0), argInt(a, "character", 0)) match
        case None => jobj(Some("found" -> ujson.Bool(false)))
        case Some(t) =>
          jobj(
            Some("symbol" -> ujson.Str(t.symbol)),
            Some("name" -> ujson.Str(t.displayName)),
            Some("type" -> ujson.Str(t.tpe))
          )
    },
    tool(
      "resolve_implicits",
      "USE INSTEAD OF grep for givens/implicits (grep cannot resolve them). Given/implicit " +
        "definitions in the index that produce a given type.",
      List(("type", "string", "the wanted type's symbol, e.g. pkg/Show#")),
      List("type")
    ) { a =>
      val typeSymbol = argStr(a, "type")
      val r = az.resolveImplicits(typeSymbol)
      jobj(
        Some("type" -> ujson.Str(typeSymbol)),
        r.chosen.map(c => "chosen" -> ujson.Str(c.symbol)),
        Some(
          "candidates" -> ujson.Arr.from(
            r.candidates.map(c =>
              jobj(Some("symbol" -> ujson.Str(c.target.symbol)), Some("type" -> ujson.Str(c.tpe)))
            )
          )
        )
      )
    },
    tool(
      "trace_implicit_chain",
      "USE INSTEAD OF grep (grep cannot follow implicit resolution). Givens producing a type and " +
        "the implicit dependencies they transitively pull in.",
      List(("type", "string", "the wanted type's symbol")),
      List("type")
    ) { a =>
      val typeSymbol = argStr(a, "type")
      val chain = az.traceImplicitChain(typeSymbol)
      jobj(
        Some("type" -> ujson.Str(typeSymbol)),
        Some(
          "steps" -> ujson.Arr.from(
            chain.steps.map(st =>
              jobj(
                Some("given" -> ujson.Str(st.target.displayName)),
                Some("type" -> ujson.Str(st.tpe)),
                opt(st.dependsOn.nonEmpty, "dependsOn" -> strs(st.dependsOn))
              )
            )
          )
        )
      )
    },
    tool(
      "call_path",
      "USE INSTEAD OF grep to trace call relationships. Shortest call path between two methods, " +
        "with the call-site edges (detailed) that realize it.",
      List(
        ("from", "string", "caller method symbol"),
        ("to", "string", "callee method symbol"),
        ("detailed", "boolean", "include call-site edge locations (default false)")
      ),
      List("from", "to")
    ) { a =>
      val p = az.callPath(argStr(a, "from"), argStr(a, "to"))
      val reachable = p.path.nonEmpty
      jobj(
        Some("from" -> ujson.Str(p.from.displayName)),
        Some("to" -> ujson.Str(p.to.displayName)),
        Some("reachable" -> ujson.Bool(reachable)),
        opt(reachable, "path" -> strs(p.path.map(_.displayName))),
        opt(
          argBool(a, "detailed", false) && p.edges.nonEmpty,
          "edges" -> strs(
            p.edges.map(e => s"${e.from.displayName}->${e.to.displayName}@${loc(e.at)}")
          )
        )
      )
    }
  )

  // --- rendering helpers ----------------------------------------------------

  private def loc(l: Location): String =
    s"${l.uri}:${l.range.start.line}:${l.range.start.character}"

  private def memberJson(mi: MemberInfo): ujson.Value =
    jobj(
      Some("name" -> ujson.Str(mi.displayName)),
      Some("kind" -> ujson.Str(mi.kind)),
      Some("symbol" -> ujson.Str(mi.symbol)),
      Some("from" -> ujson.Str(mi.declaredIn.displayName))
    )

  private def refs(rs: List[SymbolRef], detailed: Boolean): ujson.Value =
    if detailed then
      ujson.Arr.from(
        rs.map(r =>
          jobj(
            Some("symbol" -> ujson.Str(r.symbol)),
            Some("name" -> ujson.Str(r.displayName)),
            Some("kind" -> ujson.Str(r.kind))
          )
        )
      )
    else strs(rs.map(_.displayName))

  private def strs(xs: Iterable[String]): ujson.Value = ujson.Arr.from(xs.map(ujson.Str(_)))

  /** A section predicate from an optional `include` array: absent → keep all sections; present →
    * keep only the named ones.
    */
  private def includeWant(a: ujson.Value): String => Boolean =
    val include = a.obj.get("include").map(_.arr.iterator.map(_.str).toSet)
    section => include.forall(_.contains(section))

  private def notFound(symbol: String): ujson.Value =
    jobj(Some("symbol" -> ujson.Str(symbol)), Some("found" -> ujson.Bool(false)))

  /** Build an object from optional fields, dropping the absent ones (token discipline). */
  private def jobj(fields: Option[(String, ujson.Value)]*): ujson.Value =
    ujson.Obj.from(fields.flatten)

  private def opt(cond: Boolean, field: => (String, ujson.Value)): Option[(String, ujson.Value)] =
    if cond then Some(field) else None

  // --- argument + schema helpers --------------------------------------------

  private def argStr(a: ujson.Value, k: String): String = a.obj.get(k).map(_.str).getOrElse("")
  private def argInt(a: ujson.Value, k: String, d: Int): Int =
    a.obj.get(k).map(_.num.toInt).getOrElse(d)
  private def argBool(a: ujson.Value, k: String, d: Boolean): Boolean =
    a.obj.get(k).map(_.bool).getOrElse(d)

  private def tool(
      name: String,
      description: String,
      params: List[(String, String, String)],
      required: List[String]
  )(run: ujson.Value => ujson.Value): Tool =
    val props = params.map { (pname, ptype, pdesc) =>
      pname -> (ujson.Obj("type" -> ptype, "description" -> pdesc): ujson.Value)
    }
    val schema = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj.from(props),
      "required" -> strs(required)
    )
    Tool(name, description, schema, run)
