package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.model.*

/** The analysis tools exposed over MCP, with token-lean JSON rendering.
  *
  * Conventions: a symbol is a SemanticDB symbol string (e.g. `pkg/Type#method().`). Results stay
  * minimal — pass `"detailed": true` to expand the structured breakdown of a result.
  */
object McpTools:

  // Each tool falls into one of three backend categories (see the per-tool comments):
  //   • PC-only      — a position/buffer-local query the presentation compiler answers in full from
  //                    the buffer alone. When `source` is given, query ONLY the PC-regenerated
  //                    document (Analyzer.bufferOnly); the stale disk index is not consulted. The PC
  //                    is authoritative for the file, so falling back to disk would only add wrong
  //                    answers on edited buffers. → type_at_position
  //   • overlay      — a query that needs the whole-project index but wants ONE file fresher (e.g. to
  //                    resolve names referenced from other files). When `source`+`uri` are given,
  //                    overlay the buffer onto the index (Analyzer.withBuffer). → method_signature
  //   • index-only   — an inherently project-wide scan with no single-file input; reads the disk
  //                    index as-is. → find_symbol, find_usages, class_hierarchy, find_overloads,
  //                    members, resolve_implicits, trace_implicit_chain, call_path
  def all(az: Analyzer, root: java.nio.file.Path = java.nio.file.Paths.get(".")): List[Tool] = List(
    tool(
      "find_symbol",
      "Resolve a plain or partial name (e.g. 'Animal', 'show') to the SemanticDB symbol strings the " +
        "other tools require — start here whenever you have a name rather than a symbol. Ranked " +
        "exact > prefix > substring. Narrow with `exact` (name equality only), `kind` (TRAIT, CLASS, " +
        "METHOD, OBJECT, …) and `pathFilter` (glob on the symbol's definition uri).",
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
      "Every resolved reference to a symbol across the codebase, split into definitions and " +
        "references (paged) — including renames, re-exports, and inferred/implicit uses that text " +
        "search misses. Scope with `pathFilter` (glob, e.g. `core/*` or `*compat*`); drop sections " +
        "with `include` (subset of [\"definitions\",\"references\"]). `referenceCount` is always returned.",
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
      "A method's full signature: type parameters, (implicit/using) parameter lists, and return " +
        "type. Pass `uri` + `source` (the defining file's path and CURRENT text) to read it from a " +
        "buffer edited since — or never — compiled: the presentation compiler regenerates it, " +
        "error-tolerant, and overlays it on the index so types referenced from other files still " +
        "resolve. (The server must have been started with a classpath for `source` to take effect.)",
      List(
        ("symbol", "string", "method symbol"),
        ("detailed", "boolean", "include structured parameter breakdown (default false)"),
        (
          "uri",
          "string",
          "defining file's uri (path relative to project root); with `source`, overlays it"
        ),
        ("source", "string", "current full text of the file at `uri`; enables the live PC overlay")
      ),
      List("symbol")
    ) { a =>
      val symbol = argStr(a, "symbol")
      // overlay category: a referenced return/param type may be defined in another file, so the
      // buffer is overlaid ONTO the whole index rather than queried in isolation.
      val engine = (a.obj.get("uri").map(_.str), a.obj.get("source").map(_.str)) match
        case (Some(uri), Some(src)) => az.withBuffer(root.resolve(uri).toUri, src, uri)
        case _                      => az
      engine.methodSignature(symbol) match
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
      "Parents, transitive linearization, and known subtypes/implementers of a class or trait — " +
        "including subtypes anywhere in the project, which a single LSP lookup cannot give. Scope " +
        "related types with `pathFilter` (glob on a related type's definition uri); trim with " +
        "`include` (subset of [\"parents\",\"linearization\",\"knownSubtypes\"]).",
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
      "All overloads sharing a name and owner with the given method (they differ only by the `(+N)` " +
        "disambiguator in the symbol). Pass any one overload's symbol.",
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
      "Members a type declares versus those it inherits through its linearization (a member " +
        "re-declared locally counts as declared). Scope with `pathFilter` (glob on a member's " +
        "definition uri); trim with `include` (subset of [\"declared\",\"inherited\"]).",
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
      "The most specific symbol and its type at a 0-based (line, character) in a document — also the " +
        "way to turn a source location into a symbol string for the other tools. Pass `source` with " +
        "the file's CURRENT text to resolve against a buffer edited since — or never — compiled: the " +
        "presentation compiler regenerates SemanticDB in memory, error-tolerant. Without `source` it " +
        "reads the last compiled SemanticDB. (`source` needs the server started with a classpath.)",
      List(
        (
          "uri",
          "string",
          "document uri as it appears in SemanticDB (path relative to project root)"
        ),
        ("line", "integer", "0-based line"),
        ("character", "integer", "0-based column"),
        ("source", "string", "current full text of the file at `uri`; enables the live PC overlay")
      ),
      List("uri", "line", "character")
    ) { a =>
      val uri = argStr(a, "uri")
      // PC-only category: a position in one file is fully answered by the PC's regenerated document,
      // so with `source` we query THAT alone — not an overlay on the (stale-for-this-file) disk
      // index. `uri` is the index-form (relative) key; the PC needs the absolute on-disk path.
      val engine = a.obj.get("source").map(_.str) match
        case Some(src) => az.bufferOnly(root.resolve(uri).toUri, src, uri).getOrElse(az)
        case None      => az
      engine.typeAtPosition(uri, argInt(a, "line", 0), argInt(a, "character", 0)) match
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
      "The given/implicit definitions in the index that produce a wanted type (by symbol) — implicit " +
        "resolution that text search cannot do. `chosen` is set when exactly one candidate applies.",
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
      "The givens that produce a type, plus the implicit dependencies they transitively pull in — " +
        "follows implicit resolution across givens, step by step.",
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
      "The shortest call path from one method to another, with the call-site edges that realize it " +
        "(pass `detailed` for edge locations). Use for reachability / how A reaches B — for direct " +
        "callers of a single method, use find_usages.",
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
    },
    tool(
      "structure",
      "Whole-project dependency metrics from the symbol graph — use to judge what matters and where " +
        "to start. Per in-project type: afferent coupling Ca (fan-in = how many depend on it), " +
        "efferent Ce (fan-out), instability Ce/(Ca+Ce) (0 = stable foundation, 1 = unstable leaf), " +
        "`layer` (longest dependency-chain depth: 0 = foundation, higher = built on deeper chains), " +
        "`centrality` (PageRank — importance weighted by who depends on it), and cycle membership. " +
        "Over four edge dimensions (extends, memberType, call, implicit) + a combined overlay, with a " +
        "module rollup and `moduleEdges` (the module coupling surface; edges marked CYCLE are " +
        "mutual-dependency violations). High centrality / Ca, low instability / layer = core to " +
        "understand first. A cyclic node shares its cycle's layer (intra-cycle order undefined) and " +
        "is marked `inCycle` — never given a faked layer.",
      List(
        (
          "sort",
          "string",
          "rank symbols by: afferent | efferent | instability | layer | centrality | sccSize (default afferent)"
        ),
        (
          "dimension",
          "string",
          "which graph's metrics per symbol: combined | extends | memberType | call | implicit (default combined)"
        ),
        (
          "pathFilter",
          "string",
          "glob on a type's module (e.g. `core`, `*mcp*`) to scope the list"
        ),
        ("limit", "integer", "max symbols to return (default 30)"),
        (
          "detailed",
          "boolean",
          "include the per-dimension Ca/Ce/instability breakdown (default false)"
        )
      ),
      Nil
    ) { a =>
      val res = az.structure()
      val dim = argStr(a, "dimension") match
        case "" => "combined"
        case d  => d
      val sortKey = argStr(a, "sort") match
        case "" => "afferent"
        case s  => s
      val keep = res.symbols.filter(s => moduleGlob(a, s.module))
      val pick: SymbolStructure => DimensionMetrics =
        s => if dim == "combined" then s.combined else s.perDimension.getOrElse(dim, s.combined)
      val rank: DimensionMetrics => Double = m =>
        sortKey match
          case "efferent"    => m.efferent.toDouble
          case "instability" => m.instability
          case "layer"       => m.layer.toDouble
          case "centrality"  => m.centrality
          case "sccSize"     => m.sccSize.toDouble
          case _             => m.afferent.toDouble
      val ranked = keep.sortBy(s => -rank(pick(s))).take(argInt(a, "limit", 30))
      jobj(
        Some("dimension" -> ujson.Str(dim)),
        Some("sort" -> ujson.Str(sortKey)),
        Some(
          "modules" -> ujson.Arr.from(res.modules.map { m =>
            jobj(
              Some("module" -> ujson.Str(m.module)),
              Some("types" -> ujson.Num(m.typeCount)),
              Some("layer" -> ujson.Num(m.layer)),
              Some("ca" -> ujson.Num(m.afferent)),
              Some("ce" -> ujson.Num(m.efferent)),
              Some("instability" -> ujson.Num(round2(m.instability))),
              opt(m.inCycle, "inCycle" -> ujson.Bool(true))
            )
          })
        ),
        opt(
          res.moduleEdges.nonEmpty,
          "moduleEdges" -> strs(
            res.moduleEdges.map(e =>
              s"${e.from}->${e.to} (${e.weight})${if e.inCycle then " CYCLE" else ""}"
            )
          )
        ),
        Some(
          "symbols" -> ujson.Arr.from(ranked.map { s =>
            val m = pick(s)
            jobj(
              Some("symbol" -> ujson.Str(s.symbol)),
              Some("name" -> ujson.Str(s.displayName)),
              Some("module" -> ujson.Str(s.module)),
              Some("layer" -> ujson.Num(m.layer)),
              Some("ca" -> ujson.Num(m.afferent)),
              Some("ce" -> ujson.Num(m.efferent)),
              Some("instability" -> ujson.Num(round2(m.instability))),
              Some("centrality" -> ujson.Num(round3(m.centrality))),
              opt(m.inCycle, "inCycle" -> ujson.Bool(true)),
              opt(
                argBool(a, "detailed", false),
                "perDimension" -> ujson.Obj.from(s.perDimension.toList.sortBy(_._1).map { (d, dm) =>
                  d -> (jobj(
                    Some("ca" -> ujson.Num(dm.afferent)),
                    Some("ce" -> ujson.Num(dm.efferent)),
                    Some("instability" -> ujson.Num(round2(dm.instability)))
                  ): ujson.Value)
                })
              )
            )
          })
        ),
        opt(
          res.cycles.nonEmpty,
          "cycles" -> ujson.Arr.from(res.cycles.map { c =>
            jobj(
              Some("dimension" -> ujson.Str(c.dimension)),
              Some("members" -> strs(c.members))
            )
          })
        )
      )
    }
  )

  // --- rendering helpers ----------------------------------------------------

  /** Keep a module when no `pathFilter` is given, or when the glob (`*` = any chars) matches it. */
  private def moduleGlob(a: ujson.Value, module: String): Boolean =
    a.obj.get("pathFilter").map(_.str).filter(_.nonEmpty) match
      case None => true
      case Some(glob) =>
        val re = glob.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*").r
        re.findFirstIn(module).isDefined

  private def round2(d: Double): Double = math.round(d * 100.0) / 100.0
  private def round3(d: Double): Double = math.round(d * 1000.0) / 1000.0

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
