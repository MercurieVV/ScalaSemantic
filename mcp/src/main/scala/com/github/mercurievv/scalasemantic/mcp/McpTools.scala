package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.InputTypes.*
import upickle.default.ReadWriter

import scala.jdk.CollectionConverters.*

/** One `search_text` hit: a plain (not symbol-resolved) match of the query on `line` (0-based) of
  * `uri`, with the full matching line's text.
  */
private[mcp] case class SearchTextHit(uri: String, line: Int, text: String) derives ReadWriter

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
  // Tool construction is split across four sibling objects (McpToolsGroupA-D, below this object) so
  // no single generated method OR class holds every tool's construction code. `all` used to be one
  // ~800-line method building a single `List(...)` literal in this same object — that came within
  // one Stryker4s mutant of tripping the JVM's 64KB-per-method bytecode cap ("Method too large")
  // during mutation testing. Wrapping each `tool(...)` call in `toolDef` (a by-name parameter) moved
  // each tool's construction into its own synthetic method, which fixed the per-method limit — but
  // all those synthetic methods still lived on this ONE class (Scala/dotty compiles by-name/lambda
  // bodies as private methods on the enclosing class via invokedynamic, not separate class files),
  // so the next mutant tripped the JVM's per-CLASS bytecode/constant-pool cap instead ("Class too
  // large"). Moving the tool groups into physically separate objects (separate class files) is what
  // actually fixes both: each group object has its own class-level budget.
  def all(az: Analyzer, root: java.nio.file.Path = java.nio.file.Paths.get(".")): List[Tool] =
    val tools =
      McpToolsGroupA.tools(az, root) ++ McpToolsGroupB.tools(az, root) ++
        McpToolsGroupC.tools(az, root) ++ McpToolsGroupD.tools(az, root)
    // The disk index being empty means SemanticDB was never emitted/compiled for this project —
    // every index-only/overlay tool will otherwise return misleadingly plain "no results" JSON that
    // reads the same as a genuine empty match. Attach a one-line hint so the caller fixes setup
    // instead of concluding the symbol doesn't exist (or falling back to grep). type_at_position is
    // PC-only (see the category comment above `all`) and never touches `az.index`, so it's exempt.
    if az.isIndexEmpty then
      tools.map(t =>
        if t.name == "type_at_position" then t
        else t.copy(run = a => McpToolsSupport.withEmptyIndexHint(t.run(a)))
      )
    else tools

// Shared helpers used by `McpTools.all` and every `McpToolsGroup*` object. Kept as its own
// top-level object (rather than living inside `McpTools`) so the dependency between `McpTools`
// (which calls into the groups) and the groups (which use these helpers) stays one-directional —
// otherwise `McpToolsGroup*.tools` importing `McpTools.*` while `McpTools.all` calls
// `McpToolsGroup*.tools` would form a symbol-level cycle between first-party mcp-module symbols
// (caught by analysis's own dogfood test, AnalyzerStructureSuite).
private[mcp] object McpToolsSupport:
  // Passing each tool's construction as a by-name argument compiles it into its own synthetic
  // Function0-shaped method rather than inlining it into the caller — see the comment above
  // `McpTools.all` for why this alone wasn't enough and the tool groups also had to move to
  // separate objects.
  private[mcp] def toolDef(t: => Tool): Tool = t

  private[mcp] val EmptyIndexHint =
    "SemanticDB index is empty (no *.semanticdb files found) — this project likely has not been " +
      "compiled with SemanticDB enabled yet. See the Integration doc's \"Prerequisite\" section for " +
      "how to enable it for your build tool (sbt/Mill/Gradle/scalac), then recompile."

  private[mcp] def withEmptyIndexHint(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj =>
      ujson.Obj.from(o.value.toSeq :+ ("indexEmptyHint" -> ujson.Str(EmptyIndexHint)))
    case other => other

  /** Render an outline entry recursively: name/kind/line, the signature and symbol, and any nested
    * children — dropping empties for token economy.
    */
  private[mcp] def outlineJson(e: OutlineEntry): ujson.Value =
    jobj(
      Some("name" -> ujson.Str(e.name)),
      Some("kind" -> ujson.Str(e.kind.value)),
      Some("line" -> ujson.Num(e.line)),
      opt(e.signature.nonEmpty, "signature" -> ujson.Str(e.signature)),
      Some("symbol" -> ujson.Str(e.symbol)),
      opt(e.children.nonEmpty, "children" -> ujson.Arr.from(e.children.map(outlineJson)))
    )

  // --- rendering helpers ----------------------------------------------------

  /** Shared rendering for any tool that returns a WHOLE source file enriched with positioned
    * [[SourceAnnotation]]s. Tools differ only in how they COMPUTE the annotations; the
    * format/gutter/legend handling and the result envelope live here so they are written once. A
    * new source-returning tool just appends [[params]] to its schema and calls [[result]].
    */
  private[mcp] object SourceView:

    /** The schema entries every source-returning tool shares (append after its own `uri` entry). */
    val params: List[(String, String, String)] = List(
      (
        "format",
        "string",
        "annotated (default, gutter + ⟹ notes) | compilable (// ⟹ comments, valid Scala) | plain " +
          "| diff (unified diff, source → enriched, for green/red rendering)"
      ),
      (
        "annotationsOnly",
        "boolean",
        "return only the lines that carry an annotation, not the whole file (default false)"
      ),
      (
        "symbols",
        "boolean",
        "append a legend mapping each type used to its full package path (default false); with " +
          "format=compilable it also explodes wildcard/`given` imports into the explicit names used"
      ),
      (
        "docs",
        "string",
        "keep (default) | strip — drop comments for a leaner token view"
      ),
      (
        "detail",
        "string",
        "terse (default) | full — render a merged elaborated expression at each call site"
      )
    )

    /** The full tool result: the rendered `source` plus `format`, `annotationCount`, and `legend`.
      * `format`/`annotationsOnly` arrive pre-parsed from the caller (which owns the `ujson`
      * argument helpers) so this object stays self-contained — depending only on `ujson` and the
      * model, never back on [[McpTools]] (which would form a dependency cycle).
      */
    def result(
        uri: String,
        rawLines: IndexedSeq[String],
        displayLines: IndexedSeq[String],
        anns: List[SourceAnnotation],
        format: SourceFormat,
        annotationsOnly: Boolean,
        symbols: List[(String, String)] = Nil,
        lineOffset: Int = 0
    ): ujson.Value =
      val legendBlock =
        if symbols.isEmpty then ""
        else symbols.map((n, fqn) => s"//   $n → $fqn").mkString("\n// symbols:\n", "\n", "")
      val rendered = format match
        case SourceFormat.Diff => renderDiff(uri, rawLines, displayLines, anns, symbols, lineOffset)
        case _ => render(displayLines, anns, format, annotationsOnly, lineOffset) + legendBlock
      ujson.Obj(
        "uri" -> ujson.Str(uri),
        "format" -> ujson.Str(format.value),
        "annotationCount" -> ujson.Num(anns.size),
        "legend" -> ujson.Str(legend(format)),
        "source" -> ujson.Str(rendered)
      )

    /** A unified diff from the source as written (`-`) to the enriched compilable view (`+`): every
      * inline `// ⟹` note and every exploded wildcard/`given` import appears as an added line, so
      * any diff viewer colours the compiler's insertions green and the shadowed originals red. The
      * two sides are line-for-line aligned by construction (each enriched line derives from one raw
      * line; import-explosion keeps the line count), with the `symbols` legend appended as further
      * additions — so no diff algorithm is needed, only hunking with 3 lines of context.
      */
    private def renderDiff(
        uri: String,
        rawLines: IndexedSeq[String],
        displayLines: IndexedSeq[String],
        anns: List[SourceAnnotation],
        symbols: List[(String, String)],
        lineOffset: Int
    ): String =
      val byLine = anns.groupBy(_.line)
      val legendLines: List[String] =
        if symbols.isEmpty then Nil
        else "" :: "// symbols:" :: symbols.map((n, fqn) => s"//   $n → $fqn")
      // Op = (oldLine?, newLine?): a context line has old == new; a modified line differs; a legend
      // line is a pure addition (None, Some). Zip raw with display so no positional indexing is
      // needed (wartremover forbids Seq.apply).
      val body: List[(Option[String], Option[String])] =
        rawLines
          .zip(displayLines)
          .zipWithIndex
          .map { case ((raw, disp), i) =>
            val notes = byLine.getOrElse(i, Nil)
            val enriched =
              if notes.isEmpty then disp else s"$disp  // ⟹ ${notes.map(_.text).mkString("; ")}"
            (Some(raw), Some(enriched))
          }
          .toList
      val ops = body ++ legendLines.map(l => (Option.empty[String], Some(l)))
      val changed = ops.zipWithIndex.collect { case (op, i) if op._1 != op._2 => i }
      if changed.isEmpty then ""
      else
        val context = 3
        val n = ops.size
        val merged = changed
          .map(i => (math.max(0, i - context), math.min(n - 1, i + context)))
          .foldLeft(List.empty[(Int, Int)]) { (acc, r) =>
            acc match
              case (s0, e0) :: tail if r._1 <= e0 + 1 => (s0, math.max(e0, r._2)) :: tail
              case _                                  => r :: acc
          }
          .reverse
        val header = s"--- $uri (original)\n+++ $uri (enriched)"
        val hunks = merged.map { (s, e) =>
          val before = ops.take(s)
          val slice = ops.slice(s, e + 1)
          val oldBefore = before.count(_._1.isDefined)
          val newBefore = before.count(_._2.isDefined)
          val oldCount = slice.count(_._1.isDefined)
          val newCount = slice.count(_._2.isDefined)
          val oldStart = (if oldCount == 0 then oldBefore else oldBefore + 1) + lineOffset
          val newStart = (if newCount == 0 then newBefore else newBefore + 1) + lineOffset
          val hunkHeader = s"@@ -$oldStart,$oldCount +$newStart,$newCount @@"
          val lines = slice.flatMap { (o, nw) =>
            if o == nw then o.toList.map(l => s" $l")
            else o.toList.map(l => s"-$l") ++ nw.toList.map(l => s"+$l")
          }
          (hunkHeader :: lines).mkString("\n")
        }
        (header :: hunks).mkString("\n")

    /** Weave source lines and annotations into one string per the chosen format. */
    private def render(
        lines: IndexedSeq[String],
        anns: List[SourceAnnotation],
        fmt: SourceFormat,
        annotationsOnly: Boolean,
        lineOffset: Int
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
            val base = if gutter then f"${i + 1 + lineOffset}%5d  $src" else src
            if notes.isEmpty || fmt == SourceFormat.Plain then Some(base)
            else
              // Each note is self-anchored by the analysis layer (e.g. `a.map[String]`,
              // `(using Show[A])`) — the renderer just joins them, no column arithmetic.
              val joined = notes.map(_.text).mkString("; ")
              Some(
                if fmt == SourceFormat.Compilable then s"$base  // ⟹ $joined"
                else s"$base   ⟹ $joined"
              )
        }
        .mkString("\n")

    private def legend(fmt: SourceFormat): String =
      val markers =
        "Notes show compiler insertions invisible in the source: `(using …)` implicit args, " +
          "`name(…)` implicit conversion, `[…]` inferred type args, `: T` inferred type. " +
          "symbols=on adds a type→package legend."
      fmt match
        case SourceFormat.Compilable =>
          s"Valid Scala: each note is a trailing `// ⟹` comment, no line-number gutter. $markers"
        case SourceFormat.Plain =>
          "The raw file with a 1-based line-number gutter (a READ-ONLY view — edit the real file " +
            "at uri, not this). No annotations in this format."
        case SourceFormat.Annotated =>
          "READ-ONLY view, NOT valid Scala — do NOT paste into code; edit the real file at uri " +
            s"(gutter line numbers map 1:1). ⟹ marks each note. $markers"
        case SourceFormat.Diff =>
          "Unified diff, source-as-written → enriched: `-` lines are the original, `+` lines add " +
            "the compiler's inline `// ⟹` insertions and explode wildcard/`given` imports (with " +
            s"symbols=on). Render in any diff viewer for green/red. $markers"

  private[mcp] def round2(d: Double): Double = math.round(d * 100.0) / 100.0
  private[mcp] def round3(d: Double): Double = math.round(d * 1000.0) / 1000.0

  /** Optional structural-metric fields (layer / centrality / inCycle) for a type symbol, for
    * badging find_symbol / class_hierarchy results when `on`. Empty when off or the symbol is not a
    * type node.
    */
  private[mcp] def metricFields(
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

  private[mcp] def loc(l: Location): String =
    s"${l.uri}:${l.range.start.line}:${l.range.start.character}"

  /** The source lines a [[Location]] spans, gutter-numbered exactly like `annotated_source`'s
    * `plain` format (1-based, absolute file line numbers) — reuses [[SourceView.result]] on the
    * whole file so the gutter logic is never duplicated, then slices the rendered lines down to the
    * location's range. `None` when the file cannot be read.
    */
  private[mcp] def sourceSnippet(root: java.nio.file.Path, l: Location): Option[String] =
    val file = root.resolve(l.uri)
    if !java.nio.file.Files.isRegularFile(file) then None
    else
      val rawLines = java.nio.file.Files.readString(file).split("\n", -1).toIndexedSeq
      val rendered =
        SourceView.result(l.uri, rawLines, rawLines, Nil, SourceFormat.Plain, false, Nil)
      val renderedLines = rendered.obj("source").str.split("\n", -1).toIndexedSeq
      val start = math.max(0, l.range.start.line)
      val end = math.min(renderedLines.size - 1, l.range.end.line)
      if start > end then None else Some(renderedLines.slice(start, end + 1).mkString("\n"))

  /** `radius` lines around `line` (0-based), clamped to `rawLines`'s bounds — used to build
    * `find_usages`'s optional `contextLines` output.
    */
  private[mcp] def linesAround(rawLines: IndexedSeq[String], line: Int, radius: Int): List[String] =
    rawLines.slice(math.max(0, line - radius), math.min(rawLines.length, line + radius + 1)).toList

  /** Reads each distinct `uri` among `locations` at most once and pairs every [[Location]] with the
    * `contextLines`-line window around it, wrapped in a [[UsageHit]]. A file that fails to read
    * yields an empty `context` for every hit in that uri rather than failing the whole request.
    */
  private[mcp] def withContext(
      root: java.nio.file.Path,
      locations: List[Location],
      contextLines: Int
  ): List[UsageHit] =
    val rawLinesByUri = locations
      .map(_.uri)
      .distinct
      .map { uri =>
        val text =
          try Some(java.nio.file.Files.readString(root.resolve(uri)).split("\n", -1).toIndexedSeq)
          catch case _: java.io.IOException => None
        uri -> text
      }
      .toMap
    locations.map { location =>
      val context = rawLinesByUri.get(location.uri).flatten match
        case Some(rawLines) => linesAround(rawLines, location.range.start.line, contextLines)
        case None           => Nil
      UsageHit(location, context)
    }

  private[mcp] def usageHitJson(hit: UsageHit): ujson.Value =
    jobj(
      Some("location" -> ujson.Str(loc(hit.location))),
      opt(hit.context.nonEmpty, "context" -> strs(hit.context))
    )

  /** A predicate from an optional glob: `*` matches any run of chars, the rest is literal, matched
    * unanchored (substring). `None` keeps everything. Mirrors `AnalyzerHelpers.globMatcher`'s
    * semantics locally, since `search_text` is mcp-module-only and does not depend on `analysis`.
    */
  private[mcp] def mcpGlobMatcher(pattern: Option[String]): String => Boolean =
    pattern match
      case None       => _ => true
      case Some(glob) =>
        val regex = glob.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*").r
        uri => regex.findFirstIn(uri).isDefined

  /** Directory name segments to skip while walking `root` for `search_text` — build output and VCS
    * metadata are never source we want matched, and skipping them keeps the walk fast.
    */
  private[mcp] val SearchTextExcludedDirs: Set[String] =
    Set(".git", "out", "target", "node_modules", ".bloop", ".metals")

  /** Every `.scala` file under `root`, as project-relative uri strings, sorted for deterministic
    * output. Skips [[SearchTextExcludedDirs]] entirely rather than filtering after the fact.
    */
  private[mcp] def scalaFilesUnder(root: java.nio.file.Path): List[String] =
    val stream = java.nio.file.Files.walk(root)
    try
      stream
        .iterator()
        .asScala
        .filterNot(p => p.iterator().asScala.exists(seg => SearchTextExcludedDirs(seg.toString)))
        .filter(p => java.nio.file.Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .map(p => root.relativize(p).toString)
        .toList
        .sorted
    finally stream.close()

  private[mcp] def memberJson(mi: MemberInfo): ujson.Value =
    jobj(
      Some("name" -> ujson.Str(mi.displayName)),
      Some("kind" -> ujson.Str(mi.kind.value)),
      Some("symbol" -> ujson.Str(mi.symbol)),
      Some("from" -> ujson.Str(mi.declaredIn.displayName))
    )

  private[mcp] def refs(rs: List[SymbolRef], detailed: Boolean): ujson.Value =
    if detailed then
      ujson.Arr.from(
        rs.map(r =>
          jobj(
            Some("symbol" -> ujson.Str(r.symbol)),
            Some("name" -> ujson.Str(r.displayName)),
            Some("kind" -> ujson.Str(r.kind.value))
          )
        )
      )
    else strs(rs.map(_.displayName))

  private[mcp] def strs(xs: Iterable[String]): ujson.Value = ujson.Arr.from(xs.map(ujson.Str(_)))

  /** Sanitize a module name into a mermaid-safe node id (alphanumerics, `_`, `-` only). */
  private[mcp] def mermaidNodeId(module: String): String =
    module.replaceAll("[^A-Za-z0-9_-]", "_")

  /** Render `structure`'s `moduleEdges` as a `graph TD` mermaid diagram: one line per edge,
    * labelled with the weight when > 1 and `CYCLE` when the edge is part of a module-level
    * dependency cycle.
    */
  private[mcp] def structureMermaid(edges: List[ModuleEdge]): String =
    val lines = edges.map { e =>
      val from = mermaidNodeId(e.from)
      val to = mermaidNodeId(e.to)
      if e.inCycle then s"  $from -->|CYCLE| $to"
      else if e.weight > 1 then s"  $from -->|${e.weight}| $to"
      else s"  $from --> $to"
    }
    ("graph TD" :: lines).mkString("\n")

  /** A section predicate from an optional `include` array: absent → keep all sections; present →
    * keep only the named ones.
    */
  private[mcp] def includeWant(a: ujson.Value): String => Boolean =
    val include = a.obj.get("include").map(_.arr.iterator.map(_.str).toSet)
    section => include.forall(_.contains(section))

  private[mcp] def notFound(symbol: String): ujson.Value =
    jobj(Some("symbol" -> ujson.Str(symbol)), Some("found" -> ujson.Bool(false)))

  private[mcp] def notFoundUri(uri: String): ujson.Value =
    jobj(Some("uri" -> ujson.Str(uri)), Some("found" -> ujson.Bool(false)))

  /** Resolve a tool's `symbol` argument that accepts EITHER a raw SemanticDB symbol (e.g.
    * `com/ollo/SuperClass#method().`) OR a friendlier dotted FQN (e.g.
    * `com.ollo.SuperClass.method()` — a trailing `()` is stripped). Tried as a raw symbol FIRST,
    * but only accepted if it actually resolves to a definition in the index — scalameta's symbol
    * grammar happens to also accept a plain dotted FQN as a (bogus, non-existent) chain of term
    * symbols, so syntactic validity alone is not enough to tell the two forms apart. Otherwise
    * treated as an FQN: its leaf name is fed to `findSymbol(_, exact = true)`, preferring the
    * candidate whose own dotted FQN (`az.dottedFqn`) matches; on ambiguity (several same-named
    * candidates, none matching the given owner path) the first candidate is returned together with
    * a `note` explaining the fallback. `None` if the raw string resolves neither as a symbol nor to
    * any known name — shared by `symbol_source` and `source_around_position`.
    */
  private[mcp] def resolveSymbolOrFqn(az: Analyzer, raw: String): Option[(String, Option[String])] =
    val direct =
      SemanticDbSymbol.from(raw).toOption.filter(v => az.symbolDefinitionRange(v.value).isDefined)
    direct match
      case Some(v) => Some((v.value, None))
      case None    =>
        val segments = raw.stripSuffix("()").split("[.#]").filter(_.nonEmpty).toList
        segments.lastOption.flatMap { leaf =>
          val candidates = az.findSymbol(leaf, exact = true)
          val ownerMatch = candidates.find(c =>
            az.dottedFqn(c.symbol).split("\\.").toList.takeRight(segments.size) == segments
          )
          (ownerMatch, candidates) match
            case (Some(exact), _)      => Some((exact.symbol, None))
            case (None, single :: Nil) => Some((single.symbol, None))
            case (None, first :: _)    =>
              Some(
                (first.symbol, Some(s"ambiguous FQN '$raw'; using first candidate ${first.symbol}"))
              )
            case (None, Nil) => None
        }

  /** Build an object from optional fields, dropping the absent ones (token discipline). */
  private[mcp] def jobj(fields: Option[(String, ujson.Value)]*): ujson.Value =
    ujson.Obj.from(fields.flatten)

  private[mcp] def opt(
      cond: Boolean,
      field: => (String, ujson.Value)
  ): Option[(String, ujson.Value)] =
    if cond then Some(field) else None

  // --- argument + schema helpers --------------------------------------------

  private[mcp] def argStr(a: ujson.Value, k: String): String = a.obj.get(k).map(_.str).getOrElse("")
  private[mcp] def argInt(a: ujson.Value, k: String, d: Int): Int =
    a.obj.get(k).map(_.num.toInt).getOrElse(d)
  private[mcp] def argBool(a: ujson.Value, k: String, d: Boolean): Boolean =
    a.obj.get(k).map(_.bool).getOrElse(d)

  private[mcp] def validatedArg[A](result: Either[String, A]): A =
    result.fold(error, identity)

  private[mcp] def argRefinedString[A](a: ujson.Value, k: String)(
      from: String => Either[String, A]
  ): A =
    validatedArg(from(argStr(a, k)))

  private[mcp] def argRefinedString[A](
      a: ujson.Value,
      k: String,
      default: String
  )(from: String => Either[String, A]): A =
    val raw = argStr(a, k)
    validatedArg(from(if raw.isEmpty then default else raw))

  private[mcp] def argRefinedInt[A](
      a: ujson.Value,
      k: String,
      default: Int
  )(from: (Int, String) => Either[String, A]): A =
    validatedArg(from(argInt(a, k, default), k))

  private[mcp] def argSymbol(a: ujson.Value, k: String): SemanticDbSymbol =
    argRefinedString(a, k)(SemanticDbSymbol.from)

  private[mcp] def argMethodSymbol(a: ujson.Value, k: String): MethodSymbol =
    argRefinedString(a, k)(MethodSymbol.from)

  private[mcp] def argTypeSymbol(a: ujson.Value, k: String): TypeSymbol =
    argRefinedString(a, k)(TypeSymbol.from)

  private[mcp] def argPackageSymbol(a: ujson.Value, k: String): PackageSymbol =
    argRefinedString(a, k)(PackageSymbol.from)

  private[mcp] def argUri(a: ujson.Value, k: String): DocumentUri =
    argRefinedString(a, k)(DocumentUri.from)

  private[mcp] def argIdentifier(a: ujson.Value, k: String): ScalaIdentifier =
    argRefinedString(a, k)(ScalaIdentifier.from)

  private[mcp] def argIdentifier(a: ujson.Value, k: String, default: String): ScalaIdentifier =
    argRefinedString(a, k, default)(ScalaIdentifier.from)

  private[mcp] def argNonNegativeInt(a: ujson.Value, k: String, default: Int): NonNegativeInt =
    argRefinedInt(a, k, default)(NonNegativeInt.from)

  private[mcp] def argPositiveInt(a: ujson.Value, k: String, default: Int): PositiveInt =
    argRefinedInt(a, k, default)(PositiveInt.from)

  private[mcp] def argPosition(
      a: ujson.Value,
      lineKey: String,
      characterKey: String
  ): SourcePosition =
    SourcePosition
      .from(argInt(a, lineKey, 0), argInt(a, characterKey, 0))
      .fold(error, identity)

  private[mcp] def argRange(a: ujson.Value): SourceRange =
    SourceRange
      .from(
        argInt(a, "startLine", 0),
        argInt(a, "startCharacter", 0),
        argInt(a, "endLine", 0),
        argInt(a, "endCharacter", 0)
      )
      .fold(error, identity)

  private[mcp] def argDimension(a: ujson.Value, k: String): StructureDimension =
    argRefinedString(a, k)(StructureDimension.from)

  private[mcp] def argSort(a: ujson.Value, k: String): StructureSort =
    argRefinedString(a, k)(StructureSort.from)

  private[mcp] def argFormat(a: ujson.Value, k: String): SourceFormat =
    SourceFormat.from(argStr(a, k))
  private[mcp] def argDetail(a: ujson.Value, k: String): SourceDetail =
    SourceDetail.from(argStr(a, k))

  private[mcp] def error(message: String): Nothing =
    sys.error(message)

  private[mcp] def tool(
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

// Each group below is a physically separate class file (own bytecode/constant-pool budget) — see
// the comment above `McpTools.all` for why per-method splitting inside one object wasn't enough.
private[mcp] object McpToolsGroupA:
  import McpToolsSupport.*

  def tools(az: Analyzer, root: java.nio.file.Path): List[Tool] =
    List(
      toolDef(
        tool(
          "find_symbol",
          "Resolve a plain or partial name (e.g. 'Animal', 'show') to the SemanticDB symbol strings the " +
            "other tools require — start here whenever you have a name rather than a symbol. Ranked " +
            "exact > prefix > substring. Narrow with `exact` (name equality only), `kind` (TRAIT, CLASS, " +
            "METHOD, OBJECT, …) and `pathFilter` (glob on the symbol's definition uri).",
          List(
            ("query", "string", "simple or partial name to search for"),
            ("limit", "integer", "max results (default 50)"),
            (
              "exact",
              "boolean",
              "match the display name exactly, case-insensitive (default false)"
            ),
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
                      Some("kind" -> ujson.Str(r.kind.value))
                    ) ++ metricFields(az, r.symbol, withMetrics))*
                  )
                )
              )
            )
          )
        }
      ),
      toolDef(
        tool(
          "find_usages",
          "Every resolved reference to a symbol across the codebase, split into definitions and " +
            "references (paged) — including renames, re-exports, and inferred/implicit uses that text " +
            "search misses. Scope with `pathFilter` (glob, e.g. `core/*` or `*compat*`); drop sections " +
            "with `include` (subset of [\"definitions\",\"references\"]). `referenceCount` is always " +
            "returned. Set `contextLines` > 0 to also return `definitionsWithContext`/" +
            "`referencesWithContext` — each hit paired with its surrounding source lines, so you don't " +
            "have to pipe results to `rg -A`/`sed` for context.",
          List(
            ("symbol", "string", "SemanticDB symbol to search for"),
            ("limit", "integer", "max references to return (default 100)"),
            ("offset", "integer", "reference offset for paging (default 0)"),
            (
              "pathFilter",
              "string",
              "glob on document uri; `*` matches any chars (substring match)"
            ),
            (
              "include",
              "array",
              "sections to return: any of \"definitions\", \"references\" (default both)"
            ),
            (
              "contextLines",
              "integer",
              "lines of surrounding source per hit (default 0 = no context, no extra file I/O)"
            )
          ),
          List("symbol")
        ) { a =>
          val symbol = argSymbol(a, "symbol")
          val limit = argPositiveInt(a, "limit", 100)
          val offset = argNonNegativeInt(a, "offset", 0)
          val want = includeWant(a)
          val contextLines = argNonNegativeInt(a, "contextLines", 0).value
          val u = az.findUsages(symbol, a.obj.get("pathFilter").map(_.str))
          val page = u.references.slice(offset.value, offset.value + limit.value)
          val definitionsWithContext =
            if contextLines > 0 then withContext(root, u.definitions, contextLines) else Nil
          val referencesWithContext =
            if contextLines > 0 then withContext(root, page, contextLines) else Nil
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
            ),
            opt(
              want("definitions") && definitionsWithContext.nonEmpty,
              "definitionsWithContext" -> ujson.Arr.from(definitionsWithContext.map(usageHitJson))
            ),
            opt(
              want("references") && referencesWithContext.nonEmpty,
              "referencesWithContext" -> ujson.Arr.from(referencesWithContext.map(usageHitJson))
            )
          )
        }
      ),
      toolDef(
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
            (
              "source",
              "string",
              "current full text of the file at `uri`; enables the live PC overlay"
            )
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
            case None    => notFound(symbol.value)
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
        }
      ),
      toolDef(
        tool(
          "class_hierarchy",
          "Parents, transitive linearization, and known subtypes/implementers of a class or trait — " +
            "including subtypes anywhere in the project, which a single LSP lookup cannot give. Scope " +
            "related types with `pathFilter` (glob on a related type's definition uri); trim with " +
            "`include` (subset of [\"parents\",\"linearization\",\"knownSubtypes\"]).",
          List(
            ("symbol", "string", "class or trait symbol"),
            ("detailed", "boolean", "expand related types to {symbol,name,kind} (default false)"),
            (
              "pathFilter",
              "string",
              "glob on a related type's definition uri; `*` matches any chars"
            ),
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
            case None    => notFound(symbol.value)
            case Some(h) =>
              jobj(
                (List(
                  Some("symbol" -> ujson.Str(symbol.value)),
                  Some("name" -> ujson.Str(h.displayName))
                ) ++ metricFields(az, symbol.value, argBool(a, "metrics", false)) ++ List(
                  opt(
                    want("parents") && h.parents.nonEmpty,
                    "parents" -> refs(h.parents, detailed)
                  ),
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
        }
      ),
      toolDef(
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
        }
      )
    )

private[mcp] object McpToolsGroupB:
  import McpToolsSupport.*

  def tools(az: Analyzer, root: java.nio.file.Path): List[Tool] =
    List(
      toolDef(
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
            case None    => notFound(symbol.value)
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
        }
      ),
      toolDef(
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
            (
              "source",
              "string",
              "current full text of the file at `uri`; enables the live PC overlay"
            )
          ),
          List("uri", "line", "character")
        ) { a =>
          val uri = argUri(a, "uri")
          // PC-only category: a position in one file is fully answered by the PC's regenerated document,
          // so with `source` we query THAT alone — not an overlay on the (stale-for-this-file) disk
          // index. `uri` is the index-form (relative) key; the PC needs the absolute on-disk path.
          val engine = a.obj.get("source").map(_.str) match
            case Some(src) =>
              az.bufferOnly(root.resolve(uri.value).toUri, src, uri.value).getOrElse(az)
            case None => az
          engine.typeAtPosition(uri, argPosition(a, "line", "character")) match
            case None    => jobj(Some("found" -> ujson.Bool(false)))
            case Some(t) =>
              jobj(
                Some("symbol" -> ujson.Str(t.symbol)),
                Some("name" -> ujson.Str(t.displayName)),
                Some("type" -> ujson.Str(t.tpe))
              )
        }
      ),
      toolDef(
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
                  jobj(
                    Some("symbol" -> ujson.Str(c.target.symbol)),
                    Some("type" -> ujson.Str(c.tpe))
                  )
                )
              )
            )
          )
        }
      ),
      toolDef(
        tool(
          "trace_implicit_chain",
          "The givens that produce a type, plus the implicit dependencies they transitively pull in — " +
            "follows implicit resolution across givens, step by step.",
          List(
            ("type", "string", "the wanted type's symbol"),
            (
              "appliedType",
              "string",
              "optional concrete wanted type, e.g. `Show[List[Int]]`; returns one resolved tree when unambiguous"
            )
          ),
          List("type")
        ) { a =>
          val typeSymbol = argTypeSymbol(a, "type")
          val chain = az.traceImplicitChain(
            typeSymbol,
            a.obj.get("appliedType").map(_.str).filter(_.nonEmpty)
          )
          def treeJson(t: ImplicitTree): ujson.Value =
            jobj(
              Some("type" -> ujson.Str(t.targetType)),
              t.chosen.map(c => "chosen" -> ujson.Str(c.symbol)),
              opt(t.ambiguous, "ambiguous" -> ujson.Bool(true)),
              opt(t.cycle, "cycle" -> ujson.Bool(true)),
              opt(
                t.candidates.nonEmpty,
                "candidates" -> ujson.Arr.from(
                  t.candidates.map(c =>
                    jobj(
                      Some("symbol" -> ujson.Str(c.target.symbol)),
                      Some("type" -> ujson.Str(c.tpe))
                    )
                  )
                )
              ),
              opt(t.children.nonEmpty, "children" -> ujson.Arr.from(t.children.map(treeJson)))
            )
          jobj(
            Some("type" -> ujson.Str(typeSymbol.value)),
            a.obj.get("appliedType").map(v => "appliedType" -> ujson.Str(v.str)),
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
            ),
            chain.resolved.map(r => "resolved" -> treeJson(r))
          )
        }
      ),
      toolDef(
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
        }
      ),
      toolDef(
        tool(
          "method_call_hierarchy",
          "The call hierarchy for a method — callers (incoming) or callees (outgoing) — as a hierarchical " +
            "tree. `direction` is \"callers\" (who calls this method, transitively) or \"callees\" (what this " +
            "method calls, transitively). `depth` controls how many levels to expand (default 3). Cycles " +
            "are broken: a recursive call appears as a leaf with no further children. Use `call_path` to " +
            "find a shortest path between two specific methods.",
          List(
            ("symbol", "string", "method symbol"),
            (
              "direction",
              "string",
              "\"callers\" (incoming — who calls this) or \"callees\" (outgoing — what this calls); default \"callees\""
            ),
            ("depth", "integer", "how many levels to expand (default 3)")
          ),
          List("symbol")
        ) { a =>
          val symbol = argMethodSymbol(a, "symbol")
          val direction = argStr(a, "direction") match
            case "callers" => "callers"
            case _         => "callees"
          val depth = argPositiveInt(a, "depth", 3)
          val h = az.callHierarchy(symbol, depth, direction)

          def nodeJson(node: CallHierarchyNode): ujson.Value =
            jobj(
              Some("symbol" -> ujson.Str(node.method.symbol)),
              Some("name" -> ujson.Str(node.method.displayName)),
              node.at.map(l => "at" -> ujson.Str(loc(l))),
              opt(node.children.nonEmpty, "children" -> ujson.Arr.from(node.children.map(nodeJson)))
            )

          jobj(
            Some("symbol" -> ujson.Str(h.symbol)),
            Some("name" -> ujson.Str(h.displayName)),
            Some("direction" -> ujson.Str(h.direction)),
            Some("depth" -> ujson.Num(h.depth)),
            Some("hierarchy" -> nodeJson(h.root))
          )
        }
      )
    )

private[mcp] object McpToolsGroupC:
  import McpToolsSupport.*

  def tools(az: Analyzer, root: java.nio.file.Path): List[Tool] =
    List(
      toolDef(
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
            ),
            (
              "graph",
              "boolean",
              "include a `mermaid` string field rendering `moduleEdges` as a `graph TD` diagram (default false)"
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
                    "perDimension" -> ujson.Obj.from(s.perDimension.toList.sortBy(_._1).map {
                      (d, dm) =>
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
            ),
            opt(
              argBool(a, "graph", false),
              "mermaid" -> ujson.Str(structureMermaid(res.moduleEdges))
            )
          )
        }
      ),
      toolDef(
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
        }
      ),
      toolDef(
        tool(
          "annotated_source",
          "READ A SCALA FILE THIS WAY — use instead of cat/head/sed/Read for *.scala. Returns the file's " +
            "source with the compiler's invisible insertions made explicit inline: the implicit " +
            "arguments & conversions it synthesised, the type arguments it inferred, and the inferred " +
            "result/value type of every definition the source left unascribed. Each note is appended to " +
            "its line after `⟹`, self-anchored to the call it applies to (e.g. `a.map[String]`, " +
            "`(using Show[A])`); lines are 1-based. " +
            "A plain text read MISSES all of this — this shows what the compiler actually sees. Pass " +
            "`annotationsOnly` to get just the annotated lines. Pick the `format` for your need: " +
            "`annotated` (default, densest — gutter + `⟹` notes, NOT valid Scala), `compilable` (notes as " +
            "`// ⟹` comments, no gutter — valid pasteable Scala), `plain` (the raw file, no notes), or " +
            "`diff` (a unified diff from the source as written to the enriched view — `+` lines are the " +
            "compiler's insertions, render green/red in any diff viewer). In " +
            "`annotated`/`plain` the gutter is a READ-ONLY view: never paste it into code; edit the real " +
            "file at `uri` (gutter numbers map 1:1).",
          (
            "uri",
            "string",
            "document uri as it appears in SemanticDB (path relative to project root)"
          )
            :: SourceView.params,
          List("uri")
        ) { a =>
          val uri = argUri(a, "uri")
          val file = root.resolve(uri.value)
          if !java.nio.file.Files.isRegularFile(file) then notFoundUri(uri.value)
          else
            val rawLines = java.nio.file.Files.readString(file).split("\n", -1).toIndexedSeq
            val docs = argStr(a, "docs")
            val lines = if docs == "strip" then az.stripComments(rawLines) else rawLines
            val detail = argDetail(a, "detail")
            az.sourceAnnotations(uri, lines, detail) match
              case None       => notFoundUri(uri.value)
              case Some(anns) =>
                val symbolsOn = argBool(a, "symbols", false)
                val symbols = if symbolsOn then az.symbolLegend(uri) else Nil
                val fmt = argFormat(a, "format")
                // import-explosion is the compilable rendering of symbols=on (and its diff): desugar
                // wildcard / `given` imports into explicit ones so no name enters scope invisibly.
                val explode =
                  symbolsOn && (fmt == SourceFormat.Compilable || fmt == SourceFormat.Diff)
                val displayLines = if explode then az.explodeImports(uri, lines) else lines
                SourceView.result(
                  uri.value,
                  lines,
                  displayLines,
                  anns,
                  fmt,
                  argBool(a, "annotationsOnly", false),
                  symbols
                )
        }
      ),
      toolDef(
        tool(
          "symbol_source",
          "The source of ONE symbol's definition (a method's signature+body, a class's full body, a " +
            "val's initializer) — not the whole file. Accepts a SemanticDB symbol OR a friendlier " +
            "dotted FQN (`com.ollo.SuperClass.method()`, trailing `()` optional; an ambiguous FQN " +
            "resolves to the first same-named match and the result carries a `note`). Enriched " +
            "identically to `annotated_source` — same `format`/`annotationsOnly`/`symbols`/`docs`/" +
            "`detail` args and output shape — but the `source` is sliced to just the definition's " +
            "lines, with the gutter kept at the file's real (absolute) 1-based line numbers so callers " +
            "can jump straight to the real file.",
          ("symbol", "string", "SemanticDB symbol OR dotted FQN of the definition")
            :: SourceView.params,
          List("symbol")
        ) { a =>
          val raw = argStr(a, "symbol")
          resolveSymbolOrFqn(az, raw) match
            case None              => notFound(raw)
            case Some((sym, note)) =>
              az.symbolDefinitionRange(sym) match
                case None               => notFound(raw)
                case Some((uri, range)) =>
                  val file = root.resolve(uri.value)
                  if !java.nio.file.Files.isRegularFile(file) then notFoundUri(uri.value)
                  else
                    val rawLines = java.nio.file.Files.readString(file).split("\n", -1).toIndexedSeq
                    val docs = argStr(a, "docs")
                    val lines = if docs == "strip" then az.stripComments(rawLines) else rawLines
                    val detail = argDetail(a, "detail")
                    az.sourceAnnotations(uri, lines, detail) match
                      case None          => notFoundUri(uri.value)
                      case Some(allAnns) =>
                        val symbolsOn = argBool(a, "symbols", false)
                        val legendSymbols = if symbolsOn then az.symbolLegend(uri) else Nil
                        val fmt = argFormat(a, "format")
                        val explode =
                          symbolsOn && (fmt == SourceFormat.Compilable || fmt == SourceFormat.Diff)
                        val displayLines = if explode then az.explodeImports(uri, lines) else lines
                        val start = range.startLine
                        val endExclusive = math.min(range.endLine, rawLines.length)
                        val slicedRaw = lines.slice(start, endExclusive)
                        val slicedDisplay = displayLines.slice(start, endExclusive)
                        val slicedAnns = allAnns
                          .filter(ann => ann.line >= start && ann.line < endExclusive)
                          .map(ann => ann.copy(line = ann.line - start))
                        val res = SourceView.result(
                          uri.value,
                          slicedRaw,
                          slicedDisplay,
                          slicedAnns,
                          fmt,
                          argBool(a, "annotationsOnly", false),
                          legendSymbols,
                          lineOffset = start
                        )
                        note.fold(res)(n =>
                          ujson.Obj.from(res.obj.toSeq :+ ("note" -> ujson.Str(n)))
                        )
        }
      )
    )

private[mcp] object McpToolsGroupD:
  import McpToolsSupport.*

  def tools(az: Analyzer, root: java.nio.file.Path): List[Tool] =
    List(
      toolDef(
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
        }
      ),
      toolDef(
        tool(
          "batch_rename_plan",
          "Renames multiple symbols in one request by running `rename_plan` once per entry, then " +
            "detecting conflicts across the results. IMPORTANT LIMITATION: conflict detection only " +
            "catches literal edit-range overlaps (two requested renames touching the same source span) " +
            "— it does NOT detect two different symbols being renamed to the same `newName` (a semantic " +
            "collision at a shared call site), which remains the caller's responsibility. The server is " +
            "read-only: apply the returned edits yourself.",
          List(
            (
              "renames",
              "array",
              "list of {symbol: string, newName: string} entries to rename in one batch"
            )
          ),
          List("renames")
        ) { a =>
          val requests = a.obj
            .get("renames")
            .map(_.arr.toList)
            .getOrElse(Nil)
            .map(r => RenameRequest(r.obj("symbol").str, r.obj("newName").str))
          val plan = az.batchRenamePlan(requests)
          def editStr(e: RenameEdit): String =
            s"${e.uri}:${e.range.start.line}:${e.range.start.character}-${e.range.end.character}"
          jobj(
            Some(
              "perSymbol" -> ujson.Arr.from(
                plan.perSymbol.map(p =>
                  jobj(
                    Some("symbol" -> ujson.Str(p.symbol)),
                    Some("rename" -> ujson.Str(s"${p.fromName} -> ${p.toName}")),
                    Some("editCount" -> ujson.Num(p.editCount))
                  )
                )
              )
            ),
            Some("combinedEditCount" -> ujson.Num(plan.combinedEdits.size)),
            Some("combinedEdits" -> strs(plan.combinedEdits.map(editStr))),
            Some("conflictCount" -> ujson.Num(plan.conflicts.size)),
            opt(
              plan.conflicts.nonEmpty,
              "conflicts" -> ujson.Arr.from(
                plan.conflicts.map(c =>
                  jobj(
                    Some(
                      "rangeA" -> ujson.Str(
                        s"${c.uriA}:${c.rangeA.start.line}:${c.rangeA.start.character}-${c.rangeA.end.character}"
                      )
                    ),
                    Some(
                      "rangeB" -> ujson.Str(
                        s"${c.uriB}:${c.rangeB.start.line}:${c.rangeB.start.character}-${c.rangeB.end.character}"
                      )
                    ),
                    Some("reason" -> ujson.Str(c.reason))
                  )
                )
              )
            )
          )
        }
      ),
      toolDef(
        tool(
          "search_text",
          "Scoped plain-text/regex search over `.scala` files under the project root — the sanctioned " +
            "in-MCP replacement for shelling out to grep/rg. SemanticDB carries no comment/trivia model, " +
            "so this is NOT symbol-aware: it does not resolve renames, re-exports, or inferred/implicit " +
            "uses, and can over-match comments/strings/unrelated identifiers, same as grep. Use " +
            "find_symbol/find_usages for identifiers; reach for this only for string literals, " +
            "comments, or other non-symbol text.",
          List(
            ("query", "string", "text (or regex, with `regex: true`) to search for"),
            (
              "regex",
              "boolean",
              "treat `query` as a regex instead of a literal string (default false)"
            ),
            ("caseSensitive", "boolean", "match case-sensitively (default false)"),
            (
              "pathFilter",
              "string",
              "glob on document uri; `*` matches any chars (substring match)"
            ),
            ("limit", "integer", "max hits to return across all files (default 200)")
          ),
          List("query")
        ) { a =>
          val query = argStr(a, "query")
          val useRegex = argBool(a, "regex", false)
          val caseSensitive = argBool(a, "caseSensitive", false)
          val keep = mcpGlobMatcher(a.obj.get("pathFilter").map(_.str))
          val limit = argPositiveInt(a, "limit", 200).value
          val patternText = if useRegex then query else java.util.regex.Pattern.quote(query)
          val flags = if caseSensitive then 0 else java.util.regex.Pattern.CASE_INSENSITIVE
          val pattern =
            try java.util.regex.Pattern.compile(patternText, flags)
            catch
              case e: java.util.regex.PatternSyntaxException =>
                error(s"invalid regex '$query': ${e.getMessage}")
          val hits = scalaFilesUnder(root)
            .filter(keep)
            .iterator
            .flatMap { uri =>
              val text =
                try Some(java.nio.file.Files.readString(root.resolve(uri)))
                catch case _: java.io.IOException => None
              text.toList.flatMap { content =>
                content
                  .split("\n", -1)
                  .iterator
                  .zipWithIndex
                  .collect {
                    case (lineText, idx) if pattern.matcher(lineText).find() =>
                      SearchTextHit(uri, idx, lineText)
                  }
                  .toList
              }
            }
            .take(limit)
            .toList
          jobj(
            Some("query" -> ujson.Str(query)),
            Some("count" -> ujson.Num(hits.size)),
            Some(
              "hits" -> ujson.Arr.from(
                hits.map(h =>
                  jobj(
                    Some("uri" -> ujson.Str(h.uri)),
                    Some("line" -> ujson.Num(h.line)),
                    Some("text" -> ujson.Str(h.text))
                  )
                )
              )
            )
          )
        }
      ),
      toolDef(
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
        }
      ),
      toolDef(
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
            (
              "source",
              "string",
              "current full text of the file at `uri`; enables the live PC overlay"
            )
          ),
          List("uri", "startLine", "startCharacter", "endLine", "endCharacter")
        ) { a =>
          val uri = argUri(a, "uri")
          val name = argIdentifier(a, "methodName", "extracted")
          // PC-only: extraction is a single-file analysis, so when `source` is given query the PC's
          // regenerated document alone rather than overlaying the (stale-for-this-file) disk index.
          val engine = a.obj.get("source").map(_.str) match
            case Some(src) =>
              az.bufferOnly(root.resolve(uri.value).toUri, src, uri.value).getOrElse(az)
            case None => az
          engine.extractMethodPlan(
            uri,
            argRange(a),
            name
          ) match
            case None    => notFoundUri(uri.value)
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
        }
      ),
      toolDef(
        tool(
          "value_flow",
          "Trace how the value held by a val/binding/parameter propagates through the codebase — a BFS " +
            "over the value's references that follows it across method boundaries (into a RENAMED " +
            "parameter when passed as an argument, into a fresh local when re-bound) and classifies how " +
            "it leaves each node. Also follows a value into an implicit/`using` parameter — covering an " +
            "explicit `implicit`/`using` arg at the call site, a `using` parameter clause, and a context " +
            "bound (`[A: TC]`, which desugars to an implicit parameter) alike, since all three are the " +
            "same underlying mechanism. Returns a graph: `nodes` (positions where the value lives, with " +
            "`depth`), `edges` (`relation` = assigned_to | passed_as_arg | passed_as_implicit | " +
            "returned_from | method_receiver), `stoppedAt` (terminals: function_result | method_receiver " +
            "| type_widened | discarded | external_boundary | implicit_boundary — the last when a value " +
            "consumed as an implicit argument has no further in-project references) and `truncatedAt` " +
            "(nodes cut by `maxDepth`). Identify the start binding by `symbol`, or by `file` + `line` + " +
            "`column` (0-based) for a source position.",
          List(
            ("symbol", "string", "SemanticDB symbol of the val/binding/parameter to trace"),
            (
              "file",
              "string",
              "document uri (relative to project root); with `line`/`column`, an alternative to `symbol`"
            ),
            ("line", "integer", "0-based line of the binding (with `file` + `column`)"),
            ("column", "integer", "0-based column of the binding (with `file` + `line`)"),
            ("maxDepth", "integer", "max flow hops to follow from the root (default 5)"),
            (
              "stopOnTypeWidening",
              "boolean",
              "do not expand a flow into a binding of a different (widened) type (default true)"
            )
          ),
          Nil
        ) { a =>
          val symOpt: Option[SemanticDbSymbol] =
            a.obj.get("symbol").map(_.str).filter(_.nonEmpty) match
              case Some(_) => Some(argSymbol(a, "symbol"))
              case None    =>
                a.obj.get("file").map(_.str).filter(_.nonEmpty).flatMap { _ =>
                  az.typeAtPosition(argUri(a, "file"), argPosition(a, "line", "column"))
                    .flatMap(t => SemanticDbSymbol.from(t.symbol).toOption)
                }
          symOpt match
            case None      => jobj(Some("found" -> ujson.Bool(false)))
            case Some(sym) =>
              val r = az.valueFlow(
                sym,
                argPositiveInt(a, "maxDepth", 5),
                argBool(a, "stopOnTypeWidening", true)
              )
              def nodeJson(n: ValueFlowNode): ujson.Value =
                jobj(
                  Some("symbol" -> ujson.Str(n.symbol)),
                  Some("name" -> ujson.Str(n.displayName)),
                  opt(n.tpe.nonEmpty, "type" -> ujson.Str(n.tpe)),
                  n.location.map(l => "at" -> ujson.Str(loc(l))),
                  Some("depth" -> ujson.Num(n.depth)),
                  n.enclosingMethod.map(m => "enclosingMethod" -> ujson.Str(m)),
                  opt(n.kind.nonEmpty, "kind" -> ujson.Str(n.kind))
                )
              def termJson(t: ValueFlowTerminal): ujson.Value =
                jobj(
                  Some("symbol" -> ujson.Str(t.symbol)),
                  Some("classification" -> ujson.Str(t.classification)),
                  t.at.map(l => "at" -> ujson.Str(loc(l)))
                )
              jobj(
                Some("root" -> nodeJson(r.root)),
                Some("nodes" -> ujson.Arr.from(r.nodes.map(nodeJson))),
                opt(
                  r.edges.nonEmpty,
                  "edges" -> ujson.Arr.from(
                    r.edges.map(e =>
                      jobj(
                        Some("from" -> ujson.Str(e.from)),
                        Some("to" -> ujson.Str(e.to)),
                        Some("relation" -> ujson.Str(e.relation)),
                        Some("at" -> ujson.Str(loc(e.at))),
                        e.paramName.map(p => "paramName" -> ujson.Str(p)),
                        opt(e.coParameters.nonEmpty, "coParameters" -> strs(e.coParameters))
                      )
                    )
                  )
                ),
                opt(r.stoppedAt.nonEmpty, "stoppedAt" -> ujson.Arr.from(r.stoppedAt.map(termJson))),
                opt(
                  r.truncatedAt.nonEmpty,
                  "truncatedAt" -> ujson.Arr.from(r.truncatedAt.map(termJson))
                )
              )
        }
      ),
      toolDef(
        tool(
          "smart_code_duplications",
          "Analyze code duplications (clones) across the project or scoped by path. Normalizes ASTs " +
            "by abstracting over variable/internal names, literal values, and types, reporting identical " +
            "structures. Excludes nested blocks that are already reported as part of a larger clone group. " +
            "Pass `showSource: true` to also get the duplicated source lines for every occurrence " +
            "(as a `groupsSource` field), gutter-numbered like `annotated_source`'s `plain` format.",
          List(
            ("minSize", "integer", "minimum number of AST nodes to consider (default 15)"),
            ("pathFilter", "string", "glob on the document uri; `*` matches any chars"),
            (
              "showSource",
              "boolean",
              "when true, also return `groupsSource`: each group's occurrences with their duplicated " +
                "source lines as a gutter-numbered `snippet` (default false)"
            )
          ),
          List()
        ) { a =>
          val minSize = argPositiveInt(a, "minSize", 15)
          val pathFilter = a.obj.get("pathFilter").map(_.str)
          val showSource = argBool(a, "showSource", false)
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
            ),
            opt(
              showSource,
              "groupsSource" -> ujson.Arr.from(
                res.groups.map { g =>
                  jobj(
                    Some("astNodeCount" -> ujson.Num(g.astNodeCount)),
                    Some(
                      "occurrences" -> ujson.Arr.from(
                        g.occurrences.map { occ =>
                          jobj(
                            Some("location" -> ujson.Str(loc(occ.location))),
                            Some(
                              "snippet" -> ujson.Str(
                                sourceSnippet(root, occ.location).getOrElse("")
                              )
                            )
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
    )
