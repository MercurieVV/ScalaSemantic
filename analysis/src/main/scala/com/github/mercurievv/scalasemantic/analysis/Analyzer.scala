package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.analysis.graph.StructureMetrics
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import stainless.annotation.pure

import java.net.URI
import scala.meta.internal.semanticdb as s

/** The semantic query engine: turns a [[SemanticIndex]] into the result models that back the MCP
  * tools. Phase 3 covers find-usages, method-signature, and class-hierarchy.
  *
  * The index is the primary backend — disk SemanticDB from the last clean compile. An optional
  * presentation-compiler `pc` is the second backend: [[withBuffer]] regenerates one file's
  * SemanticDB in memory (error-tolerant, no compile needed) and overlays it, so the position-local
  * tools can answer about a buffer that has been edited since — or never — compiled.
  *
  * The stateless query primitives (rendering, symbol/scope/package lookups, glob predicates,
  * implicit resolution) live in [[AnalyzerHelpers]]; the methods here orchestrate them into tool
  * results.
  */
final class Analyzer(
    index: SemanticIndex,
    pc: Option[PresentationCompilerBackend] = None,
    pcSelector: Option[String => Option[PresentationCompilerBackend]] = None
):

  private val h = AnalyzerHelpers(index)
  private val backendFor: String => Option[PresentationCompilerBackend] =
    pcSelector.getOrElse(_ => pc)

  /** True when no `*.semanticdb` file was found/loaded at all — SemanticDB likely isn't enabled or
    * the project hasn't been compiled yet, as opposed to a query simply matching nothing.
    */
  def isIndexEmpty: Boolean = index.documents.isEmpty

  /** An analyzer whose index has `code` (the live contents of the file at `fileUri`) overlaid via
    * the presentation compiler, the overlaid document keyed by `docUri` so it replaces the matching
    * disk document. `fileUri` must point at a real on-disk path (the PC reads `code`, but needs a
    * resolvable file path); `docUri` is how the index addresses that file (relative to project
    * root). The returned analyzer answers every query against that fresher world; the
    * position-local tools ([[typeAtPosition]], [[methodSignature]]) benefit most, since they
    * describe a single file. Without a PC backend this is a no-op returning `this`.
    */
  def withBuffer(fileUri: URI, code: String, docUri: String): Analyzer =
    backendFor(docUri) match
      case Some(backend) =>
        new Analyzer(backend.overlay(index, fileUri, code, docUri), pc, pcSelector)
      case None => this

  /** [[withBuffer]] keyed by the file's own uri (the simple single-file case). */
  def withBuffer(uri: URI, code: String): Analyzer =
    withBuffer(uri, code, uri.toString)

  /** PC-only: an analyzer over JUST the presentation compiler's regenerated document for `fileUri`
    * (keyed by `docUri`) — the disk index is NOT consulted or merged. For position/buffer-local
    * queries where the PC is authoritative for the file and the whole-project index adds nothing;
    * cheaper than [[withBuffer]], which recomputes the full index's derived state to overlay one
    * document. `None` when there is no PC backend (the caller falls back to the disk index).
    */
  def bufferOnly(fileUri: URI, code: String, docUri: String): Option[Analyzer] =
    backendFor(docUri).map(backend =>
      new Analyzer(SemanticIndex(Vector(backend.semanticdb(fileUri, code, docUri))))
    )

  // --- smart-code-duplications ----------------------------------------------

  def analyzeDuplications(
      root: java.nio.file.Path,
      minSize: PositiveInt,
      pathFilter: Option[String] = None
  ): DuplicationsResult =
    DuplicationAnalyzer.analyze(index, root, minSize.value, pathFilter)

  // --- structure / dependency metrics ---------------------------------------

  /** Multi-relational dependency metrics for the whole project: per in-project type, the coupling
    * (Ca/Ce/instability), layer, centrality and cycle membership across the
    * `extends`/`memberType`/`call`/`implicit` graphs and their combined overlay, plus a module
    * rollup, the module coupling surface, and the list of dependency cycles. Memoised — the whole
    * graph is built once per analyzer and shared by [[structure]] and [[metricsOf]].
    */
  def structure(): StructureResult = structureResult

  @pure
  def rankedStructureSymbols(
      dimension: StructureDimension,
      sort: StructureSort,
      limit: PositiveInt,
      pathFilter: Option[String] = None
  ): List[(SymbolStructure, DimensionMetrics)] =
    val keepModule = h.globMatcher(pathFilter.filter(_.nonEmpty))
    structureResult.symbols
      .filter(sym => keepModule(sym.module))
      .map(sym => sym -> selectedMetrics(sym, dimension))
      .sortBy((_, metrics) => -rankStructureMetrics(metrics, sort))
      .take(limit.value)
      .ensuring(res => res.size <= limit.value)

  private lazy val structureResult: StructureResult = StructureMetrics(index).result()

  private lazy val structureBySymbol: Map[String, SymbolStructure] =
    structureResult.symbols.iterator.map(sym => sym.symbol -> sym).toMap

  @pure
  private def selectedMetrics(
      symbol: SymbolStructure,
      dimension: StructureDimension
  ): DimensionMetrics =
    dimension match
      case StructureDimension.Combined => symbol.combined
      case other => symbol.perDimension.getOrElse(other.value, symbol.combined)

  @pure
  private def rankStructureMetrics(metrics: DimensionMetrics, sort: StructureSort): Double =
    sort match
      case StructureSort.Afferent    => metrics.afferent.toDouble
      case StructureSort.Efferent    => metrics.efferent.toDouble
      case StructureSort.Instability => metrics.instability
      case StructureSort.Layer       => metrics.layer.toDouble
      case StructureSort.Centrality  => metrics.centrality
      case StructureSort.SccSize     => metrics.sccSize.toDouble

  /** The structural metrics for one type symbol, if it is an in-project type node — for badging the
    * results of other tools (find_symbol, class_hierarchy) with its layer/centrality/cycle status.
    */
  @pure
  def metricsOf(symbol: String): Option[SymbolStructure] = structureBySymbol.get(symbol)

  // --- document outline -----------------------------------------------------

  /** The declarations of one document, nested by enclosing scope, each with the compiler-resolved
    * signature (explicit implicits, real types) — a structural map of a file so a caller can locate
    * and understand its members without reading the whole source. `None` if the uri is not indexed.
    */
  def outline(uri: DocumentUri): Option[List[OutlineEntry]] =
    index.document(uri.value).map { doc =>
      val defs = doc.occurrences.toList
        .collect:
          case occ
              if occ.role == s.SymbolOccurrence.Role.DEFINITION && h.includeInOutline(occ.symbol) =>
            occ.symbol -> occ.range.map(_.startLine).getOrElse(0)
        .distinctBy(_._1)
      val definedSet = defs.map(_._1).toSet
      def parentOf(sym: String): Option[String] =
        Some(index.owner(sym)).filter(definedSet.contains)
      def build(sym: String, line: Int): OutlineEntry =
        val kids = defs.filter((c, _) => parentOf(c).contains(sym)).sortBy(_._2)
        OutlineEntry(
          sym,
          index.displayName(sym),
          h.kindName(sym),
          line,
          outlineSignature(sym),
          kids.map((c, l) => build(c, l))
        )
      defs.filter((sym, _) => parentOf(sym).isEmpty).sortBy(_._2).map((sym, l) => build(sym, l))
    }

  /** A one-line signature for an outline entry, rendered from SemanticDB: a method's full clarified
    * signature, a value's resolved type, or empty for a type (its name + kind already say enough).
    */
  private def outlineSignature(symbol: String): String =
    index.info(symbol).map(_.signature) match
      case Some(_: s.MethodSignature) =>
        methodSignatureOf(symbol).map(_.rendered).getOrElse("")
      case Some(v: s.ValueSignature) => s": ${h.renderType(v.tpe)}"
      case _                         => ""

  // --- annotated source -----------------------------------------------------

  /** The compiler insertions invisible in the source of `uri`, as positioned annotations: the
    * implicit arguments / conversions the compiler synthesised and the type arguments it inferred
    * (both from SemanticDB `synthetics`), plus the inferred result/value type of every definition
    * the source left unascribed. This is exactly what a plain text read of the file MISSES.
    *
    * `sourceLines` is the file's current text split on newlines — used only to drop a definition's
    * inferred-type note when the source already states the type. `None` if `uri` is not indexed.
    */
  def sourceAnnotations(
      uri: DocumentUri,
      sourceLines: IndexedSeq[String],
      detail: SourceDetail = SourceDetail.Terse
  ): Option[List[SourceAnnotation]] =
    index.document(uri.value).map { doc =>
      val synthetic0 = detail match
        case SourceDetail.Terse =>
          doc.synthetics.iterator.flatMap(h.syntheticAnnotation(_, sourceLines)).toList
        case SourceDetail.Full => fullAnnotations(doc, sourceLines)
      // Drop the redundant `(using …)` a given/def forwards into its OWN construction: the note
      // lands on the definition's header (at or before its name), and that using-param is already
      // written in the signature. A genuine call-site using in the body sits AFTER the name.
      val defStarts = doc.occurrences.iterator
        .filter(_.role == s.SymbolOccurrence.Role.DEFINITION)
        .flatMap(_.range)
        .map(r => (r.startLine, r.startCharacter))
        .toList
      val synthetic = synthetic0.filterNot(a =>
        (a.kind == "implicit" || a.kind == "full") &&
          defStarts.exists((dl, dc) => dl == a.line && dc >= a.character)
      )
      val defTypes = doc.occurrences.iterator.flatMap(h.defTypeAnnotation(_, sourceLines)).toList
      (synthetic ++ defTypes).distinct.sortBy(a => (a.line, a.character, a.kind))
    }

  /** Distinct type symbols referenced in `uri`, as (simpleName -> dotted FQN), sorted, skipping
    * `scala.*` / `java.lang.*` (universally known). Empty if `uri` is not indexed.
    */
  def symbolLegend(uri: DocumentUri): List[(String, String)] =
    val skipped = Set(
      "scala/Boolean#",
      "scala/Byte#",
      "scala/Char#",
      "scala/Double#",
      "scala/Float#",
      "scala/Int#",
      "scala/Long#",
      "scala/Short#",
      "scala/Unit#",
      "java/lang/String#"
    )
    index.document(uri.value).toList.flatMap { doc =>
      doc.occurrences.iterator
        .map(_.symbol)
        .filter(_.endsWith("#"))
        .filterNot(s => skipped.contains(s) || s.startsWith("java/lang/"))
        .toList
        .distinct
        .map(s => index.displayName(s) -> h.typeSymbolFqn(s))
        .filter((name, _) => name.nonEmpty && name != "Int" && name != "String")
        .distinct
        .sortBy(_._1)
    }

  /** Desugar wildcard / `given` imports into explicit ones — the `format=compilable` rendering of
    * `symbols=on`. Each `import X.*` / `import X.given` line is replaced by a single-line
    * `import X.member` (or braced `import X.{a, b}` for several) naming exactly the members of `X`
    * the file actually uses, so no name enters scope invisibly. The braced form stays on ONE
    * physical line, so annotation line offsets are preserved.
    *
    * "Used" is drawn from BOTH occurrences and synthetics: an implicitly-summoned given
    * (`render(3.14)` picking `doubleShow`) has no textual occurrence — only a synthetic `IdTree` —
    * so occurrences alone would miss it. A line is left untouched when its prefix or its used
    * members cannot be resolved. Limits (per the design doc): only names that entered scope THROUGH
    * the import are covered — same-package, inherited, and `export`ed names need no import and are
    * left to the `symbols` legend.
    */
  def explodeImports(uri: DocumentUri, lines: IndexedSeq[String]): IndexedSeq[String] =
    index
      .document(uri.value)
      .map { doc =>
        val used: Set[String] =
          (doc.occurrences.iterator
            .filter(_.role == s.SymbolOccurrence.Role.REFERENCE)
            .map(_.symbol) ++
            doc.synthetics.iterator.flatMap(syn => h.treeSymbols(syn.tree)))
            .filter(index.isGlobal)
            .toSet
        val membersByOwner: Map[String, List[String]] =
          used
            .groupBy(index.owner)
            .map((owner, syms) =>
              owner -> syms.iterator
                .map(index.displayName)
                .filter(_.nonEmpty)
                .toList
                .distinct
                .sorted
            )
        val importRe = """^(\s*)import\s+(.+)\.(?:\*|given)\s*$""".r
        // Resolve the import prefix TEXT to its symbol: prefer an occurrence on the import line
        // (SemanticDB records the prefix reference), else match an owner by its last name segment.
        def prefixSymbol(lineIdx: Int, prefixText: String): Option[String] =
          doc.occurrences.iterator
            .filter(o => o.role == s.SymbolOccurrence.Role.REFERENCE)
            .filter(o => o.range.exists(_.startLine == lineIdx))
            .map(_.symbol)
            .filter(sym => index.isGlobal(sym) && (sym.endsWith(".") || sym.endsWith("/")))
            .toList
            .sortBy(sym => -sym.length)
            .headOption
            .orElse:
              val last = prefixText.split('.').last
              membersByOwner.keys.find(o => index.displayName(o) == last)
        lines.iterator.zipWithIndex.map { (line, i) =>
          line match
            case importRe(indent, prefixText) =>
              val exploded =
                for
                  pfx <- prefixSymbol(i, prefixText)
                  members <- membersByOwner.get(pfx).filter(_.nonEmpty)
                yield
                  val sel = members match
                    case single :: Nil => single
                    case many          => many.mkString("{", ", ", "}")
                  s"${indent}import $prefixText.$sel"
              exploded.getOrElse(line)
            case _ => line
        }.toIndexedSeq
      }
      .getOrElse(lines)

  def stripComments(lines: IndexedSeq[String]): IndexedSeq[String] =
    h.stripComments(lines)

  private def fullAnnotations(
      doc: s.TextDocument,
      sourceLines: IndexedSeq[String]
  ): List[SourceAnnotation] =
    def rangeKey(r: s.Range): (Int, Int, Int) = (r.startLine, r.startCharacter, r.endCharacter)
    def contains(outer: s.Range, inner: s.Range): Boolean =
      outer.startLine <= inner.startLine && outer.endLine >= inner.endLine &&
        (outer.startLine < inner.startLine || outer.startCharacter <= inner.startCharacter) &&
        (outer.endLine > inner.endLine || outer.endCharacter >= inner.endCharacter)
    val typeApps = doc.synthetics.iterator.flatMap { syn =>
      syn.tree match
        case t: s.TypeApplyTree =>
          val args = t.typeArguments.map(h.renderType).mkString("[", ", ", "]")
          t.function match
            case s.SelectTree(q, Some(id)) =>
              h.typeApplyOriginalRange(q).map { r =>
                rangeKey(r) -> s".${h.renderTree(id, sourceLines)}$args"
              }
            case _ =>
              h.typeApplyOriginalRange(t.function).map { r =>
                rangeKey(r) -> args
              }
        case _ => None
    }.toMap
    val enclosingRanges = doc.synthetics.iterator.flatMap { syn =>
      syn.tree match
        case app: s.ApplyTree =>
          app.function match
            case _: s.OriginalTree => syn.range
            case _                 => None
        case _ => None
    }.toList
    val usingCalls = doc.synthetics.iterator.flatMap { syn =>
      syn.tree match
        case app: s.ApplyTree =>
          app.function match
            case _: s.OriginalTree =>
              val r = syn.range.getOrElse(s.Range.defaultInstance)
              val enclosed = enclosingRanges.exists(parent => parent != r && contains(parent, r))
              val text = h.renderTree(app, sourceLines, typeApps)
              Option.when(!enclosed && text.nonEmpty)(
                SourceAnnotation(r.startLine, r.startCharacter, "full", text)
              )
            case _ => None
        case _ => None
    }.toList
    val standaloneCalls = doc.synthetics.iterator.flatMap { syn =>
      syn.tree match
        case app: s.ApplyTree =>
          app.function match
            case _: s.OriginalTree => None
            case _                 =>
              val r = syn.range.getOrElse(s.Range.defaultInstance)
              val enclosed = enclosingRanges.exists(parent => parent != r && contains(parent, r))
              val text = h.renderTree(app, sourceLines, typeApps)
              Option.when(!enclosed && text.nonEmpty)(
                SourceAnnotation(r.startLine, r.startCharacter, "full", text)
              )
        case _ => None
    }.toList
    (usingCalls ++ standaloneCalls).distinct

  // --- rename plan ----------------------------------------------------------

  /** The precise edits to rename `symbol` to `newName`: every compiler-resolved occurrence of its
    * name (definitions and references), as `{uri, range, old→new}`. Because the occurrences come
    * from SemanticDB, this never touches comments, strings, or unrelated same-named identifiers —
    * the over-match a textual rename suffers. The MCP server is read-only, so this returns a plan
    * for the caller to apply.
    */
  @pure
  def renamePlan(symbol: SemanticDbSymbol, newName: ScalaIdentifier): RenamePlan =
    val sym = symbol.value
    val name = newName.value
    val oldName = index.displayName(sym)
    val edits = index
      .occurrencesOf(sym)
      .collect { case (uri, occ) =>
        val r = occ.range.getOrElse(s.Range.defaultInstance)
        RenameEdit(
          uri,
          Range(Position(r.startLine, r.startCharacter), Position(r.endLine, r.endCharacter)),
          oldName,
          name
        )
      }
      .distinct
      .toList
      .sortBy(e => (e.uri, e.range.start.line, e.range.start.character))
    RenamePlan(sym, oldName, name, edits.size, edits)
      .ensuring(res => res.editCount == res.edits.size)

  // --- move plan ------------------------------------------------------------

  /** The edits to move `symbol` to the package `newOwner`, keeping every call/usage resolving.
    *
    * Beyond relocating the definition, a move changes the symbol's fully-qualified name, so each
    * file that references it may need its import adjusted. Per referencing file (computed from its
    * own package, derived from SemanticDB — no text guessing of import lines):
    *   - already in `newOwner` → no import needed (omitted);
    *   - in the old package `fromOwner` → previously unqualified, now needs the new FQN imported;
    *   - elsewhere → swap the old FQN import for the new one.
    *
    * `references` carries every resolved use (so the caller sees calls/usages, not just the body).
    * `newOwner` is a package symbol (`com/foo/bar/`); the symbol's simple name is preserved.
    */
  @pure
  def movePlan(symbol: SemanticDbSymbol, newOwner: PackageSymbol): MovePlan =
    val sym = symbol.value
    val name = index.displayName(sym)
    val fromOwner = index.owner(sym)
    val toOwner = newOwner.value
    val fromFqn = h.joinFqn(h.packageDotted(fromOwner), name)
    val toFqn = h.joinFqn(h.packageDotted(toOwner), name)
    val occs = index.occurrencesOf(sym)
    val defLoc = occs.collectFirst:
      case (uri, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION =>
        h.location(uri, occ.range)
    val defUri = defLoc.map(_.uri)
    val references = occs
      .collect:
        case (uri, occ) if occ.role == s.SymbolOccurrence.Role.REFERENCE =>
          h.location(uri, occ.range)
      .distinct
      .toList
    // One import edit per referencing file, decided by that file's own package.
    val imports = references
      .map(_.uri)
      .distinct
      .filterNot(defUri.contains) // the definition's file moves with it
      .flatMap { uri =>
        val pkg = h.documentPackage(uri)
        pkg match
          case p if p.contains(toOwner)   => None // already in the destination package
          case p if p.contains(fromOwner) => Some(MoveImport(uri, "", toFqn))
          case _                          => Some(MoveImport(uri, fromFqn, toFqn))
      }
    MovePlan(
      sym,
      name,
      h.kindName(sym),
      fromOwner,
      toOwner,
      fromFqn,
      toFqn,
      defLoc,
      references,
      imports
    )

  // --- extract method plan --------------------------------------------------

  /** The plan to extract the `[start, end)` selection of `uri` into a new method `methodName`.
    *
    * Free-variable analysis over the selection's SemanticDB occurrences: a local the selection
    * READS but whose definition lies outside the selection becomes a parameter; a local the
    * selection DEFINES that is still referenced after the selection becomes part of the result.
    * This is exactly the analysis a correct extract-method needs, and it is grounded in the
    * compiler's resolved symbols and types — not text heuristics. `None` if the uri is not indexed.
    */
  def extractMethodPlan(
      uri: DocumentUri,
      range: SourceRange,
      methodName: ScalaIdentifier
  ): Option[ExtractMethodPlan] =
    val docUri = uri.value
    val name = methodName.value
    index.document(docUri).map { doc =>
      def inSelection(r: s.Range): Boolean =
        range.contains(r.startLine, r.startCharacter, r.endLine, r.endCharacter)
      def afterSelection(r: s.Range): Boolean =
        range.startsAtOrAfterEnd(r.startLine, r.startCharacter)

      val occ = doc.occurrences
      // Definition occurrences of each local, used to decide inside/outside the selection.
      val defInside = occ.iterator
        .filter(o => o.role == s.SymbolOccurrence.Role.DEFINITION && o.range.exists(inSelection))
        .map(_.symbol)
        .filter(index.isLocal)
        .toSet

      // Parameters: locals READ in the selection whose definition is NOT inside it (free vars),
      // ordered by first read position, de-duplicated.
      val params = occ.iterator
        .filter(o => o.role == s.SymbolOccurrence.Role.REFERENCE && o.range.exists(inSelection))
        .filter(o => index.isLocal(o.symbol) && !defInside.contains(o.symbol))
        .toList
        .sortBy(o => o.range.map(r => (r.startLine, r.startCharacter)).getOrElse((0, 0)))
        .map(_.symbol)
        .distinct
        .map(sym => ExtractBinding(h.localName(sym), h.localTypeText(sym)))

      // Returns: locals DEFINED inside the selection that are still READ after it.
      val readAfter = occ.iterator
        .filter(o => o.role == s.SymbolOccurrence.Role.REFERENCE && o.range.exists(afterSelection))
        .map(_.symbol)
        .toSet
      val returns = occ.iterator
        .filter(o => o.role == s.SymbolOccurrence.Role.DEFINITION && o.range.exists(inSelection))
        .filter(o => index.isLocal(o.symbol) && readAfter.contains(o.symbol))
        .toList
        .sortBy(o => o.range.map(r => (r.startLine, r.startCharacter)).getOrElse((0, 0)))
        .map(_.symbol)
        .distinct
        .map(sym => ExtractBinding(h.localName(sym), h.localTypeText(sym)))

      // The method the selection sits in: nearest preceding method definition in the file.
      val enclosing = occ
        .filter(o => o.role == s.SymbolOccurrence.Role.DEFINITION && index.isMethod(o.symbol))
        .filter(o => o.range.exists(r => range.start.atOrAfter(r.startLine, r.startCharacter)))
        .maxByOption(o => o.range.map(r => (r.startLine, r.startCharacter)).getOrElse((0, 0)))
        .map(o => h.symbolRef(o.symbol))

      val returnType = returns match
        case Nil      => "Unit"
        case b :: Nil => b.tpe
        case many     => many.map(_.tpe).mkString("(", ", ", ")")
      val paramText = params.map(p => s"${p.name}: ${p.tpe}").mkString(", ")
      val signature = s"def $name($paramText): $returnType"
      val args = params.map(_.name).mkString(", ")
      val call = returns match
        case Nil      => s"$name($args)"
        case b :: Nil => s"val ${b.name} = $name($args)"
        case many     => s"val (${many.map(_.name).mkString(", ")}) = $name($args)"

      val resultRange = Range(
        Position(range.startLine, range.startCharacter),
        Position(range.endLine, range.endCharacter)
      )
      ExtractMethodPlan(
        docUri,
        resultRange,
        name,
        enclosing,
        params,
        returns,
        returnType,
        signature,
        call
      )
    }

  // --- find-symbol ----------------------------------------------------------

  /** Find global symbols whose display name matches `query` (case-insensitive), ranked exact >
    * prefix > substring. This bridges a plain name (e.g. "Animal") to the SemanticDB symbol string
    * the other tools require — without it, callers cannot discover symbols at all. Parameters, type
    * parameters, self-params and constructors are excluded as they are never query targets.
    */
  def findSymbol(
      query: String,
      limit: PositiveInt = PositiveInt.DefaultLimit,
      exact: Boolean = false,
      kind: Option[String] = None,
      pathFilter: Option[String] = None
  ): List[SymbolRef] =
    val q = query.toLowerCase
    val wantedKind = kind.map(_.toUpperCase)
    val keepPath = h.bySymbolPath(pathFilter)
    index.symbols.values.iterator
      .filter(si => index.isGlobal(si.symbol))
      .filter(si => !findSymbolExcludedKinds.contains(si.kind))
      .filter(si => si.displayName.nonEmpty && si.displayName != "<init>")
      .filter { si =>
        val n = si.displayName.toLowerCase
        if exact then n == q else n.contains(q)
      }
      .filter(si => wantedKind.forall(k => si.kind.toString.toUpperCase == k))
      .filter(si => keepPath(si.symbol))
      .toList
      .sortBy { si =>
        val n = si.displayName.toLowerCase
        val rank = if n == q then 0 else if n.startsWith(q) then 1 else 2
        (rank, si.displayName.length, si.symbol)
      }
      .take(limit.value)
      .map(si => h.symbolRef(si.symbol))

  private val findSymbolExcludedKinds: Set[s.SymbolInformation.Kind] = Set(
    s.SymbolInformation.Kind.PARAMETER,
    s.SymbolInformation.Kind.TYPE_PARAMETER,
    s.SymbolInformation.Kind.SELF_PARAMETER
  )

  // --- find-usages ----------------------------------------------------------

  /** Every occurrence of `symbol` across all indexed documents, split into definitions and
    * references. This is inherently cross-file: occurrences are scanned over the whole index.
    *
    * `pathFilter`, when given, keeps only occurrences whose document uri matches the glob (`*` =
    * any chars, unanchored substring match) — e.g. "core" + star, or star + "compat" + star.
    * `referenceCount` reflects the filtered set.
    */
  def findUsages(symbol: SemanticDbSymbol, pathFilter: Option[String] = None): UsagesResult =
    val sym = symbol.value
    val keep = h.globMatcher(pathFilter)
    val located = index
      .occurrencesOf(sym)
      .collect:
        case (uri, occ) if keep(uri) =>
          occ.role -> h.location(uri, occ.range)
    val defs =
      located.collect { case (s.SymbolOccurrence.Role.DEFINITION, loc) => loc }.distinct.toList
    val refs =
      located.collect { case (s.SymbolOccurrence.Role.REFERENCE, loc) => loc }.distinct.toList
    UsagesResult(sym, index.displayName(sym), defs, refs)

  // --- method-signature -----------------------------------------------------

  /** Full method signature including type parameters and (implicit) parameter lists. */
  def methodSignature(symbol: MethodSymbol): Option[MethodSignature] =
    methodSignatureOf(symbol.value)

  private def methodSignatureOf(sym: String): Option[MethodSignature] =
    index.info(sym).map(_.signature).collect { case m: s.MethodSignature =>
      val name = index.displayName(sym)
      val tparams = h.scopeInfos(m.typeParameters).map(_.displayName).toList
      val plists = m.parameterLists.map { scope =>
        val params = h
          .scopeInfos(Some(scope))
          .map { p =>
            Parameter(p.displayName, h.renderType(h.valueType(p)), h.isImplicit(p))
          }
          .toList
        ParameterList(params, params.nonEmpty && params.forall(_.isImplicit))
      }.toList
      val ret = h.renderType(m.returnType)
      MethodSignature(
        sym,
        name,
        tparams,
        plists,
        ret,
        h.renderMethod(name, tparams, plists, ret)
      )
    }

  // --- class-hierarchy ------------------------------------------------------

  /** Parents, transitive linearization, and known subtypes (the latter is something Metals cannot
    * answer directly — it requires scanning every type in the index).
    *
    * `pathFilter`, when given, keeps only related types whose own definition uri matches the glob
    * (`*` = any chars, substring match) — scoping each list (parents, linearization, subtypes) to a
    * subdirectory.
    */
  def classHierarchy(
      symbol: TypeSymbol,
      pathFilter: Option[String] = None
  ): Option[ClassHierarchy] =
    val sym = symbol.value
    val keep = h.bySymbolPath(pathFilter)
    index.info(sym).map(_.signature).collect { case c: s.ClassSignature =>
      val parents = c.parents.flatMap(h.parentSymbol).filter(keep).map(h.symbolRef).toList
      ClassHierarchy(
        sym,
        index.displayName(sym),
        parents,
        h.linearize(sym).filter(keep).map(h.symbolRef),
        h.knownSubtypes(sym).filter(keep).map(h.symbolRef)
      )
    }

  // --- find-overloads -------------------------------------------------------

  /** All methods sharing the owner and simple name of `symbol` (overloads differ only by the `(+N)`
    * disambiguator in their symbol string). Works given any one of the overloads.
    */
  def findOverloads(symbol: MethodSymbol): OverloadsResult =
    val sym = symbol.value
    val name = index.displayName(sym)
    val own = index.owner(sym)
    val overloads = index.symbols.values
      .collect:
        case si
            if index
              .isMethod(si.symbol) && index.owner(si.symbol) == own && si.displayName == name =>
          si.symbol
      .toList
      .sorted
      .flatMap(methodSignatureOf)
    OverloadsResult(name, overloads)

  // --- trait-vs-local members -----------------------------------------------

  /** Members declared directly on a class/trait versus those inherited from its linearization. An
    * inherited member that is re-declared locally (overridden) is reported only as declared.
    *
    * `pathFilter`, when given, keeps only members whose own definition uri matches the glob (`*` =
    * any chars, substring match) — e.g. to drop members inherited from types outside a module.
    */
  def members(symbol: TypeSymbol, pathFilter: Option[String] = None): Option[MembersResult] =
    val sym = symbol.value
    val keep = h.bySymbolPath(pathFilter)
    index.info(sym).map(_.signature).collect { case _: s.ClassSignature =>
      val declared = h.declarationSymbols(sym).filter(keep).map(h.memberInfo(_, sym))
      val declaredNames = declared.map(_.displayName).toSet
      val inherited = h
        .linearize(sym)
        .flatMap(parent => h.declarationSymbols(parent).map(h.memberInfo(_, parent)))
        .filterNot(m => declaredNames.contains(m.displayName))
        .filter(m => keep(m.symbol))
        .distinctBy(_.displayName)
      MembersResult(sym, index.displayName(sym), declared, inherited)
    }

  // --- type-at-position -----------------------------------------------------

  /** The most specific symbol whose occurrence range covers the given 0-based position. */
  @pure
  def typeAtPosition(uri: DocumentUri, position: SourcePosition): Option[TypeAtPosition] =
    val docUri = uri.value
    index
      .document(docUri)
      .toSeq
      .flatMap(_.occurrences)
      .filter(occ =>
        occ.range.exists(h.rangeContains(_, position.lineValue, position.characterValue))
      )
      .minByOption(occ => occ.range.map(h.rangeSpan).getOrElse(Long.MaxValue))
      .map { occ =>
        TypeAtPosition(
          h.location(docUri, occ.range),
          occ.symbol,
          index.displayName(occ.symbol),
          h.typeString(occ.symbol)
        )
      }
      .ensuring(res => res.forall(_.location.uri == uri.value))

  // --- resolve-implicits ----------------------------------------------------

  /** Implicit/given instances in the index that produce the given type (by symbol). For a `given`
    * object this is a parent it extends; for a `given def` it is the (possibly synthetic) return
    * type's parent. `chosen` is set only when exactly one candidate exists.
    */
  @pure
  def resolveImplicits(typeSymbol: TypeSymbol): ImplicitResolution =
    val sym = typeSymbol.value
    val candidates = h.implicitsProducing(sym).map { si =>
      ImplicitCandidate(
        h.symbolRef(si.symbol),
        h.renderType(h.producedType(si)),
        fromExplicitImport = false
      )
    }
    val chosen = candidates match
      case one :: Nil => Some(one.target)
      case _          => None
    ImplicitResolution(sym, chosen, candidates)
      .ensuring(res =>
        res.chosen.isDefined == (res.candidates.size == 1) && res.chosen.forall(c =>
          res.candidates.headOption.exists(_.target == c)
        )
      )

  // --- trace-implicit-chain -------------------------------------------------

  /** Givens producing `typeSymbol`, plus the implicit dependencies they pull in, walked
    * transitively. Each step records the implicit-parameter types it `dependsOn`.
    */
  def traceImplicitChain(
      typeSymbol: TypeSymbol,
      appliedType: Option[String] = None
  ): ImplicitChain =
    val sym = typeSymbol.value
    def loop(
        queue: List[String],
        seenTypes: List[String],
        steps: List[ImplicitChainStep]
    ): List[ImplicitChainStep] =
      queue match
        case Nil         => steps
        case tpe :: rest =>
          if seenTypes.contains(tpe) then loop(rest, seenTypes, steps)
          else
            val produced = h.implicitsProducing(tpe)
            val newSteps = produced.map { si =>
              val deps = h.implicitDependencyHeads(si)
              ImplicitChainStep(h.symbolRef(si.symbol), h.renderType(h.producedType(si)), deps)
            }
            val nextTypes = produced.flatMap(h.implicitDependencyHeads)
            loop(rest ::: nextTypes, tpe :: seenTypes, steps ::: newSteps)
    ImplicitChain(
      sym,
      loop(List(sym), Nil, Nil).distinctBy(_.target.symbol),
      appliedType.map(resolveImplicitTree)
    )

  private case class TypePattern(name: String, args: List[TypePattern]):
    def rendered: String =
      if args.isEmpty then name else args.map(_.rendered).mkString(s"$name[", ", ", "]")

  private def resolveImplicitTree(targetType: String): ImplicitTree =
    def typeSymbolForName(name: String): Option[String] =
      index.symbols.values
        .filter(si => index.isType(si.symbol) && si.displayName == name)
        .toList
        .sortBy(_.symbol)
        .headOption
        .map(_.symbol)

    def parseType(s0: String): TypePattern =
      val s = s0.trim
      val bracket = s.indexOf('[')
      if bracket < 0 then TypePattern(s, Nil)
      else
        val name = s.take(bracket).trim
        val inside = s.drop(bracket + 1).dropRight(1)
        TypePattern(name, splitTypeArgs(inside).map(parseType))

    def splitTypeArgs(s: String): List[String] =
      val (parts, last, _) = s.foldLeft((List.empty[String], "", 0)) {
        case ((acc, cur, depth), ',') if depth == 0 =>
          (cur.trim :: acc, "", depth)
        case ((acc, cur, depth), '[') =>
          (acc, s"$cur[", depth + 1)
        case ((acc, cur, depth), ']') =>
          (acc, s"$cur]", depth - 1)
        case ((acc, cur, depth), c) =>
          (acc, s"$cur$c", depth)
      }
      (last.trim :: parts).filter(_.nonEmpty).reverse

    def unify(
        pattern: TypePattern,
        wanted: TypePattern,
        tparams: Set[String],
        bindings: Map[String, TypePattern]
    ): Option[Map[String, TypePattern]] =
      if tparams.contains(pattern.name) && pattern.args.isEmpty then
        bindings.get(pattern.name) match
          case Some(bound) if bound == wanted => Some(bindings)
          case Some(_)                        => None
          case None                           => Some(bindings.updated(pattern.name, wanted))
      else if pattern.name == wanted.name && pattern.args.size == wanted.args.size then
        pattern.args.zip(wanted.args).foldLeft(Option(bindings)) {
          case (Some(acc), (p, w)) => unify(p, w, tparams, acc)
          case (None, _)           => None
        }
      else None

    def substitute(tpe: TypePattern, bindings: Map[String, TypePattern]): TypePattern =
      bindings.getOrElse(tpe.name, tpe.copy(args = tpe.args.map(substitute(_, bindings))))

    def build(target: TypePattern, seen: Set[String]): ImplicitTree =
      val targetText = target.rendered
      if seen.contains(targetText) then
        ImplicitTree(targetText, None, Nil, Nil, ambiguous = false, cycle = true)
      else
        val candidates = typeSymbolForName(target.name).toList.flatMap { head =>
          h.implicitsProducing(head).flatMap { si =>
            val produced = parseType(h.renderType(h.producedType(si)))
            unify(produced, target, h.typeParameterNames(si), Map.empty).map(si -> _)
          }
        }
        val renderedCandidates = candidates.map { (si, _) =>
          ImplicitCandidate(
            h.symbolRef(si.symbol),
            h.renderType(h.producedType(si)),
            fromExplicitImport = false
          )
        }
        val chosen = candidates match
          case (si, bindings) :: Nil =>
            val children = h.implicitDependencyTypes(si).map { dep =>
              build(substitute(parseType(h.renderType(dep)), bindings), seen + targetText)
            }
            ImplicitTree(
              targetText,
              Some(h.symbolRef(si.symbol)),
              renderedCandidates,
              children,
              ambiguous = false,
              cycle = false
            )
          case _ =>
            ImplicitTree(
              targetText,
              None,
              renderedCandidates,
              Nil,
              ambiguous = candidates.size > 1,
              cycle = false
            )
        chosen

    build(parseType(targetType), Set.empty)

  // --- call-graph path-find -------------------------------------------------

  /** Shortest call path `from -> ... -> to`, with the call-site edges that realize it. Empty `path`
    * means `to` is unreachable from `from`.
    */
  def callPath(from: MethodSymbol, to: MethodSymbol): CallGraphPath =
    val fromSym = from.value
    val toSym = to.value
    val adjacency = callGraph
    def bfs(frontier: List[List[String]], seen: Set[String]): List[String] =
      frontier match
        case Nil                          => Nil
        case (path @ (node :: _)) :: rest =>
          if node == toSym then path.reverse
          else
            val nexts = adjacency.getOrElse(node, Nil).map(_._1).filterNot(seen.contains)
            bfs(rest ::: nexts.map(_ :: path), seen ++ nexts)
        case _ :: rest => bfs(rest, seen) // unreachable: paths are always non-empty
    // bfs already yields List(fromSym) when fromSym == toSym (it matches on the first node), so no
    // special case is needed for the trivial path.
    val nodes = bfs(List(List(fromSym)), Set(fromSym))
    val edges = nodes
      .zip(nodes.drop(1))
      .flatMap { (a, b) =>
        adjacency.getOrElse(a, Nil).find(_._1 == b).map { (_, loc) =>
          CallEdge(h.symbolRef(a), h.symbolRef(b), loc)
        }
      }
    CallGraphPath(h.symbolRef(fromSym), h.symbolRef(toSym), nodes.map(h.symbolRef), edges)

  // --- call hierarchy -------------------------------------------------------

  /** The call hierarchy for `symbol` as a tree, up to `depth` levels, in the given `direction`
    * ("callers" = incoming, "callees" = outgoing). Cycles are broken by tracking visited symbols
    * per path so that a recursive call appears as a leaf with no children.
    */
  def callHierarchy(
      symbol: MethodSymbol,
      depth: PositiveInt,
      direction: String
  ): CallHierarchy =
    val sym = symbol.value
    val adj: Map[String, List[(String, Location)]] =
      if direction == "callers" then reverseCallGraph else callGraph

    def buildNode(
        current: String,
        at: Option[Location],
        remaining: Int,
        visited: Set[String]
    ): CallHierarchyNode =
      val children =
        if remaining <= 0 || visited.contains(current) then Nil
        else
          adj.getOrElse(current, Nil).map { (child, loc) =>
            buildNode(child, Some(loc), remaining - 1, visited + current)
          }
      CallHierarchyNode(h.symbolRef(current), at, children)

    val root = buildNode(sym, None, depth.value, Set.empty)
    CallHierarchy(sym, index.displayName(sym), direction, depth.value, root)

  // --- value flow -----------------------------------------------------------

  /** Trace how the value held by a val/binding/parameter propagates through the codebase: a BFS
    * from `symbol` over its references, classifying each as a flow relation and following it across
    * method boundaries — into a renamed parameter when the value is passed as an argument, into a
    * fresh local when it is re-bound, etc. A node with no outgoing flow is a terminal, classified
    * by how the value left it (`function_result`, `method_receiver`, `external_boundary`,
    * `discarded`); a flow into a binding of a different head type is recorded but not expanded when
    * `stopOnTypeWidening` (terminal `type_widened`); nodes reached at `maxDepth` are reported in
    * `truncatedAt` (`depth_limit`).
    *
    * Classification is occurrence-grounded (no AST): per reference, co-located occurrences on the
    * same line decide the relation — a preceding local definition with no intervening call is an
    * assignment; a preceding method reference makes the value an argument, positionally matched to
    * that method's value parameter; a method reference immediately after the value is a receiver
    * use; a value in tail position of its enclosing method body is a return.
    */
  def valueFlow(
      symbol: SemanticDbSymbol,
      maxDepth: PositiveInt,
      stopOnTypeWidening: Boolean
  ): ValueFlowResult =
    val start = symbol.value
    val limit = maxDepth.value
    import s.SymbolOccurrence.Role.{DEFINITION, REFERENCE}
    import scala.math.Ordering.Implicits.infixOrderingOps

    final case class Flow(
        relation: String,
        to: Option[String],
        at: Location,
        paramName: Option[String] = None,
        coParameters: List[String] = Nil
    )

    def isValueRef(sym: String): Boolean =
      !index.isMethod(sym) && !index.isType(sym) && !index.isPackage(sym)

    def headType(sym: String): Option[String] =
      index.info(sym).map(h.valueType).flatMap(h.parentSymbol)

    def widened(from: String, to: String): Boolean =
      (headType(from), headType(to)) match
        case (Some(a), Some(b)) => a != b
        case _                  => false

    def definitionLocation(sym: String): Option[Location] =
      index
        .occurrencesOf(sym)
        .collectFirst:
          case (uri, occ) if occ.role == DEFINITION => h.location(uri, occ.range)

    def node(sym: String, depth: Int): ValueFlowNode =
      val encMethodSym = index.ownerChain(sym).find(index.isMethod)
      ValueFlowNode(
        sym,
        index.displayName(sym),
        h.typeString(sym),
        definitionLocation(sym),
        depth,
        encMethodSym.map(index.displayName),
        h.kindName(sym).value
      )

    def valueParamSymbols(methodSym: String): List[String] =
      index
        .info(methodSym)
        .map(_.signature)
        .collect { case m: s.MethodSignature =>
          m.parameterLists.flatMap(sc => h.scopeInfos(Some(sc)).map(_.symbol)).toList
        }
        .getOrElse(Nil)

    def startCol(o: s.SymbolOccurrence): Int = o.range.map(_.startCharacter).getOrElse(0)
    def endCol(o: s.SymbolOccurrence): Int = o.range.map(_.endCharacter).getOrElse(0)
    def pos(o: s.SymbolOccurrence): (Int, Int) =
      (o.range.map(_.startLine).getOrElse(0), startCol(o))

    // Is `occ` the last value reference in its enclosing method body — a tail/return position?
    def isTailReturn(doc: s.TextDocument, occ: s.SymbolOccurrence): Boolean =
      val ordered = doc.occurrences.sortBy(pos)
      val methodDefs = ordered.filter(o => o.role == DEFINITION && index.isMethod(o.symbol))
      val p = pos(occ)
      methodDefs.filter(m => pos(m) <= p).lastOption.exists { enclosing =>
        val mPos = pos(enclosing)
        val nextDef = methodDefs.find(d => pos(d) > mPos).map(pos)
        val body = ordered.filter { o =>
          val q = pos(o)
          q > mPos && nextDef.forall(q < _) && o.role == REFERENCE && isValueRef(o.symbol)
        }
        body.lastOption.contains(occ)
      }

    def classifyRef(uri: String, doc: s.TextDocument, occ: s.SymbolOccurrence): Option[Flow] =
      def maxByStartCol(occs: Seq[s.SymbolOccurrence]): Option[s.SymbolOccurrence] =
        occs.foldLeft(Option.empty[s.SymbolOccurrence]) { (acc, x) =>
          acc match
            case None      => Some(x)
            case Some(max) => if startCol(x) > startCol(max) then Some(x) else Some(max)
        }

      occ.range.map { r =>
        val col = r.startCharacter
        val rEnd = r.endCharacter
        val at = h.location(uri, Some(r))
        val onLine = doc.occurrences.filter(_.range.exists(_.startLine == r.startLine))
        val receiver = onLine.exists(o =>
          o.role == REFERENCE && index.isMethod(o.symbol) && startCol(o) == rEnd + 1
        )
        if receiver then Flow("method_receiver", None, at)
        else
          val before = onLine.filter(o => startCol(o) < col)
          val methodBefore = before.filter(o => o.role == REFERENCE && index.isMethod(o.symbol))
          val localDefBefore =
            before.filter(o =>
              o.role == DEFINITION && index.isLocal(o.symbol) && isValueRef(o.symbol)
            )
          val maxLocalDef = maxByStartCol(localDefBefore)
          val maxMethodBefore = maxByStartCol(methodBefore)
          maxMethodBefore match
            case None =>
              maxLocalDef match
                case Some(ld) => Flow("assigned_to", Some(ld.symbol), at)
                case None     =>
                  if isTailReturn(doc, occ) then Flow("returned_from", None, at)
                  else Flow("discarded", None, at)
            case Some(callee) =>
              val calleeEnd = endCol(callee)
              val argIndex = onLine.count(o =>
                o.role == REFERENCE && isValueRef(o.symbol) &&
                  o.range.exists(rg => rg.startCharacter > calleeEnd && rg.startCharacter < col)
              )
              val allParams = valueParamSymbols(callee.symbol)
              allParams.lift(argIndex) match
                case Some(p) =>
                  val pName = index.displayName(p)
                  val coPars = allParams.filterNot(_ == p).map(index.displayName)
                  Flow("passed_as_arg", Some(p), at, Some(pName), coPars)
                case None => Flow("passed_as_arg", None, at)
      }

    def classify(sym: String): List[Flow] =
      index
        .occurrencesOf(sym)
        .iterator
        .collect { case (uri, occ) if occ.role == REFERENCE => (uri, occ) }
        .flatMap((uri, occ) => index.document(uri).flatMap(classifyRef(uri, _, occ)))
        .toList

    def terminalClass(flows: List[Flow]): String =
      val cs = flows.map:
        case Flow("returned_from", _, _, _, _)    => "function_result"
        case Flow("method_receiver", _, _, _, _)  => "method_receiver"
        case Flow("passed_as_arg", None, _, _, _) => "external_boundary"
        case _                                    => "discarded"
      List("function_result", "method_receiver", "external_boundary", "discarded")
        .find(cs.contains)
        .getOrElse("discarded")

    @annotation.tailrec
    def loop(
        queue: List[(String, Int)],
        visited: Set[String],
        nodes: List[ValueFlowNode],
        edges: List[ValueFlowEdge],
        stopped: List[ValueFlowTerminal],
        truncated: List[ValueFlowTerminal]
    ): (
        List[ValueFlowNode],
        List[ValueFlowEdge],
        List[ValueFlowTerminal],
        List[ValueFlowTerminal]
    ) =
      queue match
        case Nil => (nodes.reverse, edges.reverse, stopped.reverse, truncated.reverse)
        case (sym, _) :: rest if visited(sym) =>
          loop(rest, visited, nodes, edges, stopped, truncated)
        case (sym, depth) :: rest =>
          val visited2 = visited + sym
          val n = node(sym, depth)
          val nodes2 = n :: nodes
          if depth >= limit then
            loop(
              rest,
              visited2,
              nodes2,
              edges,
              stopped,
              ValueFlowTerminal(sym, "depth_limit", n.location) :: truncated
            )
          else
            val flows = classify(sym)
            val edgeFlows = flows.filter(_.to.isDefined)
            val realEdges =
              edgeFlows
                .flatMap(f =>
                  f.to.map(to =>
                    ValueFlowEdge(sym, to, f.relation, f.at, f.paramName, f.coParameters)
                  )
                )
                .distinct
            val targets = edgeFlows.flatMap(_.to).distinct
            val (widenTargets, flowTargets) =
              if stopOnTypeWidening then targets.partition(widened(sym, _)) else (Nil, targets)
            val widenNodes = widenTargets.map(node(_, depth + 1))
            val widenTerms =
              widenTargets.map(t => ValueFlowTerminal(t, "type_widened", definitionLocation(t)))
            val symTerminal =
              if edgeFlows.nonEmpty then Nil
              else List(ValueFlowTerminal(sym, terminalClass(flows), n.location))
            val enqueue = flowTargets.filterNot(visited2).map((_, depth + 1))
            loop(
              rest ++ enqueue,
              visited2,
              widenNodes.reverse ::: nodes2,
              realEdges.reverse ::: edges,
              widenTerms.reverse ::: symTerminal.reverse ::: stopped,
              truncated
            )

    val (nodes, edges, stopped, truncated) =
      loop(List(start -> 0), Set.empty, Nil, Nil, Nil, Nil)
    ValueFlowResult(
      node(start, 0),
      nodes.distinctBy(_.symbol),
      edges.distinct,
      stopped.distinctBy(t => (t.symbol, t.classification)),
      truncated.distinctBy(_.symbol)
    )

  /** Caller -> list of (callee, call-site) edges, attributing each method reference to the most
    * recent method definition in source order within its document.
    */
  private lazy val callGraph: Map[String, List[(String, Location)]] =
    val edges = index.documents.flatMap { doc =>
      val ordered = doc.occurrences.sortBy(o =>
        (o.range.map(_.startLine).getOrElse(0), o.range.map(_.startCharacter).getOrElse(0))
      )
      ordered
        .foldLeft((Option.empty[String], List.empty[(String, String, Location)])):
          case ((_, acc), occ)
              if occ.role == s.SymbolOccurrence.Role.DEFINITION && index.isMethod(occ.symbol) =>
            (Some(occ.symbol), acc)
          case ((Some(current), acc), occ) if index.isMethod(occ.symbol) && current != occ.symbol =>
            (Some(current), (current, occ.symbol, h.location(doc.uri, occ.range)) :: acc)
          case (state, _) =>
            state
        ._2
        .reverse
    }
    edges.toList.groupMap(_._1)(e => (e._2, e._3))

  /** Callee -> list of (caller, call-site) edges: the reverse of `callGraph`. */
  private lazy val reverseCallGraph: Map[String, List[(String, Location)]] =
    callGraph.toList
      .flatMap { (caller, callees) =>
        callees.map { (callee, loc) => callee -> (caller, loc) }
      }
      .groupMap(_._1)(_._2)
