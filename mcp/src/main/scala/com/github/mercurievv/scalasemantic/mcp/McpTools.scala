package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.InputTypes.*

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
        ("pathFilter", "string", "glob on the symbol's definition uri; `*` matches any chars"),
        (
          "metrics",
          "boolean",
          "badge each result type with its structural layer/centrality/inCycle (default false)"
        )
      ),
      List("query")
    ) { a =>
      val q = argStr(a, "query")
      val withMetrics = argBool(a, "metrics", false)
      val results = az.findSymbol(
        q,
        argPositiveInt(a, "limit", 50),
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
                (List(
                  Some("symbol" -> ujson.Str(r.symbol)),
                  Some("name" -> ujson.Str(r.displayName)),
                  Some("kind" -> ujson.Str(r.kind))
                ) ++ metricFields(az, r.symbol, withMetrics))*
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
      val symbol = argSymbol(a, "symbol")
      val limit = argPositiveInt(a, "limit", 100)
      val offset = argNonNegativeInt(a, "offset", 0)
      val want = includeWant(a)
      val u = az.findUsages(symbol, a.obj.get("pathFilter").map(_.str))
      val page = u.references.slice(offset.value, offset.value + limit.value)
      jobj(
        Some("symbol" -> ujson.Str(symbol.value)),
        Some("name" -> ujson.Str(u.displayName)),
        opt(
          want("definitions") && u.definitions.nonEmpty,
          "definitions" -> strs(u.definitions.map(loc))
        ),
        Some("referenceCount" -> ujson.Num(u.references.size)),
        opt(want("references") && page.nonEmpty, "references" -> strs(page.map(loc))),
        opt(
          want("references") && offset.value + limit.value < u.references.size,
          "nextOffset" -> ujson.Num(offset.value + limit.value)
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
      val symbol = argMethodSymbol(a, "symbol")
      // overlay category: a referenced return/param type may be defined in another file, so the
      // buffer is overlaid ONTO the whole index rather than queried in isolation.
      val engine = (a.obj.get("uri").map(_.str), a.obj.get("source").map(_.str)) match
        case (Some(_), Some(src)) =>
          val uri = argUri(a, "uri")
          az.withBuffer(root.resolve(uri.value).toUri, src, uri.value)
        case _ => az
      engine.methodSignature(symbol) match
        case None => notFound(symbol.value)
        case Some(m) =>
          if !argBool(a, "detailed", false) then
            jobj(
              Some("symbol" -> ujson.Str(symbol.value)),
              Some("signature" -> ujson.Str(m.rendered))
            )
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
              Some("symbol" -> ujson.Str(symbol.value)),
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
        ),
        (
          "metrics",
          "boolean",
          "badge the queried type with its structural layer/centrality/inCycle (default false)"
        )
      ),
      List("symbol")
    ) { a =>
      val symbol = argTypeSymbol(a, "symbol")
      val detailed = argBool(a, "detailed", false)
      val want = includeWant(a)
      az.classHierarchy(symbol, a.obj.get("pathFilter").map(_.str)) match
        case None => notFound(symbol.value)
        case Some(h) =>
          jobj(
            (List(
              Some("symbol" -> ujson.Str(symbol.value)),
              Some("name" -> ujson.Str(h.displayName))
            ) ++ metricFields(az, symbol.value, argBool(a, "metrics", false)) ++ List(
              opt(want("parents") && h.parents.nonEmpty, "parents" -> refs(h.parents, detailed)),
              opt(
                want("linearization") && h.linearization.nonEmpty,
                "linearization" -> refs(h.linearization, detailed)
              ),
              opt(
                want("knownSubtypes") && h.knownSubtypes.nonEmpty,
                "knownSubtypes" -> refs(h.knownSubtypes, detailed)
              )
            ))*
          )
    },
    tool(
      "find_overloads",
      "All overloads sharing a name and owner with the given method (they differ only by the `(+N)` " +
        "disambiguator in the symbol). Pass any one overload's symbol.",
      List(("symbol", "string", "any one overload's symbol")),
      List("symbol")
    ) { a =>
      val o = az.findOverloads(argMethodSymbol(a, "symbol"))
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
      val symbol = argTypeSymbol(a, "symbol")
      val detailed = argBool(a, "detailed", false)
      val want = includeWant(a)
      az.members(symbol, a.obj.get("pathFilter").map(_.str)) match
        case None => notFound(symbol.value)
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
            Some("symbol" -> ujson.Str(symbol.value)),
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
      val uri = argUri(a, "uri")
      // PC-only category: a position in one file is fully answered by the PC's regenerated document,
      // so with `source` we query THAT alone — not an overlay on the (stale-for-this-file) disk
      // index. `uri` is the index-form (relative) key; the PC needs the absolute on-disk path.
      val engine = a.obj.get("source").map(_.str) match
        case Some(src) => az.bufferOnly(root.resolve(uri.value).toUri, src, uri.value).getOrElse(az)
        case None      => az
      engine.typeAtPosition(uri, argPosition(a, "line", "character")) match
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
      val typeSymbol = argTypeSymbol(a, "type")
      val r = az.resolveImplicits(typeSymbol)
      jobj(
        Some("type" -> ujson.Str(typeSymbol.value)),
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
      val typeSymbol = argTypeSymbol(a, "type")
      val chain = az.traceImplicitChain(typeSymbol)
      jobj(
        Some("type" -> ujson.Str(typeSymbol.value)),
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
      val p = az.callPath(argMethodSymbol(a, "from"), argMethodSymbol(a, "to"))
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
      val dim = argDimension(a, "dimension")
      val sortKey = argSort(a, "sort")
      val ranked = az.rankedStructureSymbols(
        dim,
        sortKey,
        argPositiveInt(a, "limit", 30),
        a.obj.get("pathFilter").map(_.str)
      )
      jobj(
        Some("dimension" -> ujson.Str(dim.value)),
        Some("sort" -> ujson.Str(sortKey.value)),
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
          "symbols" -> ujson.Arr.from(ranked.map { (s, m) =>
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
    },
    tool(
      "document_outline",
      "USE INSTEAD OF reading a whole file. A structural map of a Scala file: its types and members " +
        "nested by scope, each with kind, 0-based definition line, and a signature rendered from the " +
        "compiler (explicit implicit/using params, real resolved types — not the source's inferred " +
        "text). Use it to survey a file's API and locate where to edit without reading the source.",
      List(
        (
          "uri",
          "string",
          "document uri as it appears in SemanticDB (path relative to project root)"
        )
      ),
      List("uri")
    ) { a =>
      val uri = argUri(a, "uri")
      az.outline(uri) match
        case None =>
          jobj(Some("uri" -> ujson.Str(uri.value)), Some("found" -> ujson.Bool(false)))
        case Some(entries) =>
          jobj(
            Some("uri" -> ujson.Str(uri.value)),
            Some("outline" -> ujson.Arr.from(entries.map(outlineJson)))
          )
    },
    tool(
      "annotated_source",
      "READ A SCALA FILE THIS WAY — use instead of cat/head/sed/Read for *.scala. Returns the file's " +
        "source with the compiler's invisible insertions made explicit inline: the implicit " +
        "arguments & conversions it synthesised, the type arguments it inferred, and the inferred " +
        "result/value type of every definition the source left unascribed. Each note is appended to " +
        "its line after `⟹` (with `col N`, 1-based, when it pins a precise spot); lines are 1-based. " +
        "A plain text read MISSES all of this — this shows what the compiler actually sees. Pass " +
        "`annotationsOnly` to get just the annotated lines. Pick the `format` for your need: " +
        "`annotated` (default, densest — gutter + `⟹` notes, NOT valid Scala), `compilable` (notes as " +
        "`// ⟹` comments, no gutter — valid pasteable Scala), or `plain` (the raw file, no notes). In " +
        "`annotated`/`plain` the gutter is a READ-ONLY view: never paste it into code; edit the real " +
        "file at `uri` (gutter numbers map 1:1).",
      ("uri", "string", "document uri as it appears in SemanticDB (path relative to project root)")
        :: SourceView.params,
      List("uri")
    ) { a =>
      val uri = argUri(a, "uri")
      val file = root.resolve(uri.value)
      if !java.nio.file.Files.isRegularFile(file) then notFoundUri(uri.value)
      else
        val lines = java.nio.file.Files.readString(file).split("\n", -1).toIndexedSeq
        az.sourceAnnotations(uri, lines) match
          case None => notFoundUri(uri.value)
          case Some(anns) =>
            SourceView.result(
              uri.value,
              lines,
              anns,
              argFormat(a, "format"),
              argBool(a, "annotationsOnly", false)
            )
    },
    tool(
      "rename_plan",
      "The precise edits to rename a symbol everywhere it is used. Returns every compiler-resolved " +
        "occurrence of the name (definitions + references) as `uri:line:col-col` ranges to replace " +
        "with the new name — no grep over-match (comments, strings, unrelated same-named identifiers " +
        "are never touched). The server is read-only: apply the returned edits yourself.",
      List(
        ("symbol", "string", "the symbol to rename"),
        ("newName", "string", "the new simple name")
      ),
      List("symbol", "newName")
    ) { a =>
      val p = az.renamePlan(argSymbol(a, "symbol"), argIdentifier(a, "newName"))
      jobj(
        Some("symbol" -> ujson.Str(p.symbol)),
        Some("rename" -> ujson.Str(s"${p.fromName} -> ${p.toName}")),
        Some("editCount" -> ujson.Num(p.editCount)),
        Some(
          "edits" -> strs(
            p.edits.map(e =>
              s"${e.uri}:${e.range.start.line}:${e.range.start.character}-${e.range.end.character}"
            )
          )
        )
      )
    },
    tool(
      "move_plan",
      "The edits to move a top-level definition (class/object/trait/def/val) to another package " +
        "while keeping every call and usage compiling — not just the definition itself. Three parts: " +
        "the `definition` location to cut and re-place under `newOwner`; every compiler-resolved " +
        "`reference` to it across the project (so you can confirm nothing is missed); and the per-file " +
        "`imports` to add/remove, since the move changes the symbol's fully-qualified name (a file " +
        "already in the destination package needs none). `newOwner` is the destination package symbol, " +
        "e.g. `com/foo/bar/`; the simple name is unchanged. Read-only — apply the edits yourself.",
      List(
        ("symbol", "string", "the symbol to move"),
        ("newOwner", "string", "destination package symbol, e.g. `com/foo/bar/`")
      ),
      List("symbol", "newOwner")
    ) { a =>
      val p = az.movePlan(argSymbol(a, "symbol"), argPackageSymbol(a, "newOwner"))
      jobj(
        Some("symbol" -> ujson.Str(p.symbol)),
        Some("move" -> ujson.Str(s"${p.fromFqn} -> ${p.toFqn}")),
        p.definition.map(d => "definition" -> ujson.Str(loc(d))),
        Some("referenceCount" -> ujson.Num(p.references.size)),
        opt(p.references.nonEmpty, "references" -> strs(p.references.map(loc))),
        opt(
          p.imports.nonEmpty,
          "imports" -> ujson.Arr.from(
            p.imports.map(i =>
              jobj(
                Some("uri" -> ujson.Str(i.uri)),
                opt(i.removeImport.nonEmpty, "remove" -> ujson.Str(i.removeImport)),
                opt(i.addImport.nonEmpty, "add" -> ujson.Str(i.addImport))
              )
            )
          )
        )
      )
    },
    tool(
      "extract_method_plan",
      "The edits to extract a selected source range into a new method — both the new method AND the " +
        "call that replaces the selection. From the compiler's resolved symbols/types in the range: " +
        "locals it READS but does not define become the parameters (with real types); locals it " +
        "DEFINES that later code still uses become the return. Returns the `signature` to insert in " +
        "the enclosing scope and the `call` to put where the selection was. A binding name is always " +
        "exact; an inferred local val whose type SemanticDB did not record renders as `?` for you to " +
        "fill. Give the selection as start/end line+character (0-based, end exclusive). Pass `source` " +
        "(the file's CURRENT text) to analyse a buffer edited since — or never — compiled (needs a " +
        "classpath-started server). Read-only — apply the edits yourself.",
      List(
        ("uri", "string", "document uri (path relative to project root)"),
        ("startLine", "integer", "0-based start line of the selection"),
        ("startCharacter", "integer", "0-based start column of the selection"),
        ("endLine", "integer", "0-based end line of the selection (exclusive end)"),
        ("endCharacter", "integer", "0-based end column of the selection (exclusive)"),
        ("methodName", "string", "name for the extracted method (default `extracted`)"),
        ("source", "string", "current full text of the file at `uri`; enables the live PC overlay")
      ),
      List("uri", "startLine", "startCharacter", "endLine", "endCharacter")
    ) { a =>
      val uri = argUri(a, "uri")
      val name = argIdentifier(a, "methodName", "extracted")
      // PC-only: extraction is a single-file analysis, so when `source` is given query the PC's
      // regenerated document alone rather than overlaying the (stale-for-this-file) disk index.
      val engine = a.obj.get("source").map(_.str) match
        case Some(src) => az.bufferOnly(root.resolve(uri.value).toUri, src, uri.value).getOrElse(az)
        case None      => az
      engine.extractMethodPlan(
        uri,
        argRange(a),
        name
      ) match
        case None => notFoundUri(uri.value)
        case Some(p) =>
          jobj(
            Some("uri" -> ujson.Str(p.uri)),
            p.enclosingMethod.map(m => "enclosingMethod" -> ujson.Str(m.displayName)),
            Some("signature" -> ujson.Str(p.signature)),
            Some("call" -> ujson.Str(p.call)),
            opt(
              p.parameters.nonEmpty,
              "parameters" -> strs(p.parameters.map(b => s"${b.name}: ${b.tpe}"))
            ),
            opt(
              p.returns.nonEmpty,
              "returns" -> strs(p.returns.map(b => s"${b.name}: ${b.tpe}"))
            ),
            Some("returnType" -> ujson.Str(p.returnType))
          )
    },
    tool(
      "smart_code_duplications",
      "Analyze code duplications (clones) across the project or scoped by path. Normalizes ASTs " +
        "by abstracting over variable/internal names, literal values, and types, reporting identical " +
        "structures. Excludes nested blocks that are already reported as part of a larger clone group.",
      List(
        ("minSize", "integer", "minimum number of AST nodes to consider (default 15)"),
        ("pathFilter", "string", "glob on the document uri; `*` matches any chars")
      ),
      List()
    ) { a =>
      val minSize = argPositiveInt(a, "minSize", 15)
      val pathFilter = a.obj.get("pathFilter").map(_.str)
      val res = az.analyzeDuplications(root, minSize, pathFilter)
      jobj(
        Some("groupsCount" -> ujson.Num(res.groups.size)),
        Some(
          "groups" -> ujson.Arr.from(
            res.groups.map { g =>
              jobj(
                Some("occurrencesCount" -> ujson.Num(g.size)),
                Some("astNodeCount" -> ujson.Num(g.astNodeCount)),
                Some(
                  "occurrences" -> ujson.Arr.from(
                    g.occurrences.map { occ =>
                      jobj(
                        Some("location" -> ujson.Str(loc(occ.location))),
                        occ.enclosingMethod.map(m => "enclosingMethod" -> ujson.Str(m))
                      )
                    }
                  )
                )
              )
            }
          )
        )
      )
    }
  )

  /** Render an outline entry recursively: name/kind/line, the signature and symbol, and any nested
    * children — dropping empties for token economy.
    */
  private def outlineJson(e: OutlineEntry): ujson.Value =
    jobj(
      Some("name" -> ujson.Str(e.name)),
      Some("kind" -> ujson.Str(e.kind)),
      Some("line" -> ujson.Num(e.line)),
      opt(e.signature.nonEmpty, "signature" -> ujson.Str(e.signature)),
      Some("symbol" -> ujson.Str(e.symbol)),
      opt(e.children.nonEmpty, "children" -> ujson.Arr.from(e.children.map(outlineJson)))
    )

  // --- rendering helpers ----------------------------------------------------

  /** Shared rendering for any tool that returns a WHOLE source file enriched with positioned
    * [[SourceAnnotation]]s. Tools differ only in how they COMPUTE the annotations; the
    * format/gutter/legend/`col N` handling and the result envelope live here so they are written
    * once. A new source-returning tool just appends [[params]] to its schema and calls [[result]].
    */
  private object SourceView:

    /** The schema entries every source-returning tool shares (append after its own `uri` entry). */
    val params: List[(String, String, String)] = List(
      (
        "format",
        "string",
        "annotated (default, gutter + ⟹ notes) | compilable (// ⟹ comments, valid Scala) | plain"
      ),
      (
        "annotationsOnly",
        "boolean",
        "return only the lines that carry an annotation, not the whole file (default false)"
      )
    )

    /** The full tool result: the rendered `source` plus `format`, `annotationCount`, and `legend`.
      * `format`/`annotationsOnly` arrive pre-parsed from the caller (which owns the `ujson`
      * argument helpers) so this object stays self-contained — depending only on `ujson` and the
      * model, never back on [[McpTools]] (which would form a dependency cycle).
      */
    def result(
        uri: String,
        lines: IndexedSeq[String],
        anns: List[SourceAnnotation],
        format: SourceFormat,
        annotationsOnly: Boolean
    ): ujson.Value =
      ujson.Obj(
        "uri" -> ujson.Str(uri),
        "format" -> ujson.Str(format.value),
        "annotationCount" -> ujson.Num(anns.size),
        "legend" -> ujson.Str(legend(format)),
        "source" -> ujson.Str(render(lines, anns, format, annotationsOnly))
      )

    /** Weave source lines and annotations into one string per the chosen format. */
    private def render(
        lines: IndexedSeq[String],
        anns: List[SourceAnnotation],
        fmt: SourceFormat,
        annotationsOnly: Boolean
    ): String =
      val byLine = anns.groupBy(_.line)
      // `plain` shows no notes, so `annotationsOnly` would be empty — ignore it there.
      val onlyAnnotated = fmt != SourceFormat.Plain && annotationsOnly
      val gutter = fmt != SourceFormat.Compilable // a line-number gutter is not valid Scala
      lines.iterator.zipWithIndex
        .flatMap { case (src, i) =>
          val notes = byLine.getOrElse(i, Nil)
          if onlyAnnotated && notes.isEmpty then None
          else
            val base = if gutter then f"${i + 1}%5d  $src" else src
            if notes.isEmpty || fmt == SourceFormat.Plain then Some(base)
            else
              val joined = notes.map(noteText).mkString("; ")
              Some(
                if fmt == SourceFormat.Compilable then s"$base  // ⟹ $joined"
                else s"$base   ⟹ $joined"
              )
        }
        .mkString("\n")

    /** Kinds whose `character` is a precise call site, so a `col N` prefix points the reader at the
      * exact call the note applies to (vs. the using-arg note, whose range is only the enclosing
      * point).
      */
    private val preciseColKinds = Set("inferred-type-args", "implicit-conversion")

    /** An annotation's display text, prefixed with a 1-based `col N` when its column is
      * trustworthy.
      */
    private def noteText(n: SourceAnnotation): String =
      if preciseColKinds.contains(n.kind) then s"col ${n.character + 1} ${n.text}" else n.text

    private def legend(fmt: SourceFormat): String =
      val markers =
        "Notes show compiler insertions invisible in the source: `(using …)` implicit args, " +
          "`name(…)` implicit conversion, `[…]` inferred type args, `: T` inferred type; `col N` " +
          "(1-based) pins the call a note applies to."
      fmt match
        case SourceFormat.Compilable =>
          s"Valid Scala: each note is a trailing `// ⟹` comment, no line-number gutter. $markers"
        case SourceFormat.Plain =>
          "The raw file with a 1-based line-number gutter (a READ-ONLY view — edit the real file " +
            "at uri, not this). No annotations in this format."
        case SourceFormat.Annotated =>
          "READ-ONLY view, NOT valid Scala — do NOT paste into code; edit the real file at uri " +
            s"(gutter line numbers map 1:1). ⟹ marks each note. $markers"

  private def round2(d: Double): Double = math.round(d * 100.0) / 100.0
  private def round3(d: Double): Double = math.round(d * 1000.0) / 1000.0

  /** Optional structural-metric fields (layer / centrality / inCycle) for a type symbol, for
    * badging find_symbol / class_hierarchy results when `on`. Empty when off or the symbol is not a
    * type node.
    */
  private def metricFields(
      az: Analyzer,
      symbol: String,
      on: Boolean
  ): List[Option[(String, ujson.Value)]] =
    if !on then Nil
    else
      az.metricsOf(symbol).toList.flatMap { s =>
        List(
          Some("layer" -> ujson.Num(s.combined.layer)),
          Some("centrality" -> ujson.Num(round3(s.combined.centrality))),
          opt(s.combined.inCycle, "inCycle" -> ujson.Bool(true))
        )
      }

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

  private def notFoundUri(uri: String): ujson.Value =
    jobj(Some("uri" -> ujson.Str(uri)), Some("found" -> ujson.Bool(false)))

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

  private def argSymbol(a: ujson.Value, k: String): SemanticDbSymbol =
    SemanticDbSymbol.from(argStr(a, k)).fold(error, identity)

  private def argMethodSymbol(a: ujson.Value, k: String): MethodSymbol =
    MethodSymbol.from(argStr(a, k)).fold(error, identity)

  private def argTypeSymbol(a: ujson.Value, k: String): TypeSymbol =
    TypeSymbol.from(argStr(a, k)).fold(error, identity)

  private def argPackageSymbol(a: ujson.Value, k: String): PackageSymbol =
    PackageSymbol.from(argStr(a, k)).fold(error, identity)

  private def argUri(a: ujson.Value, k: String): DocumentUri =
    DocumentUri.from(argStr(a, k)).fold(error, identity)

  private def argIdentifier(a: ujson.Value, k: String): ScalaIdentifier =
    ScalaIdentifier.from(argStr(a, k)).fold(error, identity)

  private def argIdentifier(a: ujson.Value, k: String, default: String): ScalaIdentifier =
    val raw = argStr(a, k)
    ScalaIdentifier.from(if raw.isEmpty then default else raw).fold(error, identity)

  private def argNonNegativeInt(a: ujson.Value, k: String, default: Int): NonNegativeInt =
    NonNegativeInt.from(argInt(a, k, default), k).fold(error, identity)

  private def argPositiveInt(a: ujson.Value, k: String, default: Int): PositiveInt =
    PositiveInt.from(argInt(a, k, default), k).fold(error, identity)

  private def argPosition(a: ujson.Value, lineKey: String, characterKey: String): SourcePosition =
    SourcePosition
      .from(argInt(a, lineKey, 0), argInt(a, characterKey, 0))
      .fold(error, identity)

  private def argRange(a: ujson.Value): SourceRange =
    SourceRange
      .from(
        argInt(a, "startLine", 0),
        argInt(a, "startCharacter", 0),
        argInt(a, "endLine", 0),
        argInt(a, "endCharacter", 0)
      )
      .fold(error, identity)

  private def argDimension(a: ujson.Value, k: String): StructureDimension =
    StructureDimension.from(argStr(a, k)).fold(error, identity)

  private def argSort(a: ujson.Value, k: String): StructureSort =
    StructureSort.from(argStr(a, k)).fold(error, identity)

  private def argFormat(a: ujson.Value, k: String): SourceFormat =
    SourceFormat.from(argStr(a, k))

  private def error(message: String): Nothing =
    sys.error(message)

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
