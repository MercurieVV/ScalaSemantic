package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.analysis.graph.StructureMetrics
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

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
    pc: Option[PresentationCompilerBackend] = None
):

  private val h = AnalyzerHelpers(index)

  /** An analyzer whose index has `code` (the live contents of the file at `fileUri`) overlaid via
    * the presentation compiler, the overlaid document keyed by `docUri` so it replaces the matching
    * disk document. `fileUri` must point at a real on-disk path (the PC reads `code`, but needs a
    * resolvable file path); `docUri` is how the index addresses that file (relative to project
    * root). The returned analyzer answers every query against that fresher world; the
    * position-local tools ([[typeAtPosition]], [[methodSignature]]) benefit most, since they
    * describe a single file. Without a PC backend this is a no-op returning `this`.
    */
  def withBuffer(fileUri: URI, code: String, docUri: String): Analyzer =
    pc match
      case Some(backend) => new Analyzer(backend.overlay(index, fileUri, code, docUri), pc)
      case None          => this

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
    pc.map(backend =>
      new Analyzer(SemanticIndex(Vector(backend.semanticdb(fileUri, code, docUri))))
    )

  // --- structure / dependency metrics ---------------------------------------

  /** Multi-relational dependency metrics for the whole project: per in-project type, the coupling
    * (Ca/Ce/instability), layer, centrality and cycle membership across the
    * `extends`/`memberType`/`call`/`implicit` graphs and their combined overlay, plus a module
    * rollup, the module coupling surface, and the list of dependency cycles. Memoised — the whole
    * graph is built once per analyzer and shared by [[structure]] and [[metricsOf]].
    */
  def structure(): StructureResult = structureResult

  private lazy val structureResult: StructureResult = StructureMetrics(index).result()

  private lazy val structureBySymbol: Map[String, SymbolStructure] =
    structureResult.symbols.iterator.map(s => s.symbol -> s).toMap

  /** The structural metrics for one type symbol, if it is an in-project type node — for badging the
    * results of other tools (find_symbol, class_hierarchy) with its layer/centrality/cycle status.
    */
  def metricsOf(symbol: String): Option[SymbolStructure] = structureBySymbol.get(symbol)

  // --- document outline -----------------------------------------------------

  /** The declarations of one document, nested by enclosing scope, each with the compiler-resolved
    * signature (explicit implicits, real types) — a structural map of a file so a caller can locate
    * and understand its members without reading the whole source. `None` if the uri is not indexed.
    */
  def outline(uri: String): Option[List[OutlineEntry]] =
    index.document(uri).map { doc =>
      val defs = doc.occurrences.toList
        .collect {
          case occ
              if occ.role == s.SymbolOccurrence.Role.DEFINITION && h.includeInOutline(occ.symbol) =>
            occ.symbol -> occ.range.map(_.startLine).getOrElse(0)
        }
        .distinctBy(_._1)
      val definedSet = defs.map(_._1).toSet
      def parentOf(sym: String): Option[String] =
        val o = index.owner(sym)
        if definedSet.contains(o) then Some(o) else None
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
      case Some(_: s.MethodSignature) => methodSignature(symbol).map(_.rendered).getOrElse("")
      case Some(v: s.ValueSignature)  => s": ${h.renderType(v.tpe)}"
      case _                          => ""

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
      uri: String,
      sourceLines: IndexedSeq[String]
  ): Option[List[SourceAnnotation]] =
    index.document(uri).map { doc =>
      val synthetic = doc.synthetics.iterator.flatMap(h.syntheticAnnotation).toList
      val defTypes = doc.occurrences.iterator.flatMap(h.defTypeAnnotation(_, sourceLines)).toList
      (synthetic ++ defTypes).distinct.sortBy(a => (a.line, a.character, a.kind))
    }

  // --- rename plan ----------------------------------------------------------

  /** The precise edits to rename `symbol` to `newName`: every compiler-resolved occurrence of its
    * name (definitions and references), as `{uri, range, old→new}`. Because the occurrences come
    * from SemanticDB, this never touches comments, strings, or unrelated same-named identifiers —
    * the over-match a textual rename suffers. The MCP server is read-only, so this returns a plan
    * for the caller to apply.
    */
  def renamePlan(symbol: String, newName: String): RenamePlan =
    val oldName = index.displayName(symbol)
    val edits = index
      .occurrencesOf(symbol)
      .collect { case (uri, occ) =>
        val r = occ.range.getOrElse(s.Range.defaultInstance)
        RenameEdit(
          uri,
          Range(Position(r.startLine, r.startCharacter), Position(r.endLine, r.endCharacter)),
          oldName,
          newName
        )
      }
      .distinct
      .toList
      .sortBy(e => (e.uri, e.range.start.line, e.range.start.character))
    RenamePlan(symbol, oldName, newName, edits.size, edits)

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
  def movePlan(symbol: String, newOwner: String): MovePlan =
    val name = index.displayName(symbol)
    val fromOwner = index.owner(symbol)
    val toOwner = if newOwner.endsWith("/") || newOwner.isEmpty then newOwner else s"$newOwner/"
    val fromFqn = h.joinFqn(h.packageDotted(fromOwner), name)
    val toFqn = h.joinFqn(h.packageDotted(toOwner), name)
    val occs = index.occurrencesOf(symbol)
    val defLoc = occs.collectFirst {
      case (uri, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION =>
        h.location(uri, occ.range)
    }
    val defUri = defLoc.map(_.uri)
    val references = occs
      .collect {
        case (uri, occ) if occ.role == s.SymbolOccurrence.Role.REFERENCE =>
          h.location(uri, occ.range)
      }
      .distinct
      .toList
    // One import edit per referencing file, decided by that file's own package.
    val imports = references
      .map(_.uri)
      .distinct
      .filterNot(defUri.contains) // the definition's file moves with it
      .flatMap { uri =>
        val pkg = h.documentPackage(uri)
        if pkg.contains(toOwner) then None // already in the destination package
        else if pkg.contains(fromOwner) then Some(MoveImport(uri, "", toFqn))
        else Some(MoveImport(uri, fromFqn, toFqn))
      }
    MovePlan(
      symbol,
      name,
      h.kindName(symbol),
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
      uri: String,
      startLine: Int,
      startChar: Int,
      endLine: Int,
      endChar: Int,
      methodName: String
  ): Option[ExtractMethodPlan] =
    index.document(uri).map { doc =>
      def inSelection(r: s.Range): Boolean =
        val afterStart =
          r.startLine > startLine || (r.startLine == startLine && r.startCharacter >= startChar)
        val beforeEnd =
          r.endLine < endLine || (r.endLine == endLine && r.endCharacter <= endChar)
        afterStart && beforeEnd
      def afterSelection(r: s.Range): Boolean =
        r.startLine > endLine || (r.startLine == endLine && r.startCharacter >= endChar)

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
        .filter(o =>
          o.range.exists(r =>
            r.startLine < startLine ||
              (r.startLine == startLine && r.startCharacter <= startChar)
          )
        )
        .maxByOption(o => o.range.map(r => (r.startLine, r.startCharacter)).getOrElse((0, 0)))
        .map(o => h.symbolRef(o.symbol))

      val returnType = returns match
        case Nil      => "Unit"
        case b :: Nil => b.tpe
        case many     => many.map(_.tpe).mkString("(", ", ", ")")
      val paramText = params.map(p => s"${p.name}: ${p.tpe}").mkString(", ")
      val signature = s"def $methodName($paramText): $returnType"
      val args = params.map(_.name).mkString(", ")
      val call = returns match
        case Nil      => s"$methodName($args)"
        case b :: Nil => s"val ${b.name} = $methodName($args)"
        case many     => s"val (${many.map(_.name).mkString(", ")}) = $methodName($args)"

      val range = Range(Position(startLine, startChar), Position(endLine, endChar))
      ExtractMethodPlan(
        uri,
        range,
        methodName,
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
      limit: Int = 50,
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
      .take(limit)
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
  def findUsages(symbol: String, pathFilter: Option[String] = None): UsagesResult =
    val keep = h.globMatcher(pathFilter)
    val located = index.occurrencesOf(symbol).collect {
      case (uri, occ) if keep(uri) =>
        occ.role -> h.location(uri, occ.range)
    }
    val defs =
      located.collect { case (s.SymbolOccurrence.Role.DEFINITION, loc) => loc }.distinct.toList
    val refs =
      located.collect { case (s.SymbolOccurrence.Role.REFERENCE, loc) => loc }.distinct.toList
    UsagesResult(symbol, index.displayName(symbol), defs, refs)

  // --- method-signature -----------------------------------------------------

  /** Full method signature including type parameters and (implicit) parameter lists. */
  def methodSignature(symbol: String): Option[MethodSignature] =
    index.info(symbol).map(_.signature).collect { case m: s.MethodSignature =>
      val name = index.displayName(symbol)
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
        symbol,
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
  def classHierarchy(symbol: String, pathFilter: Option[String] = None): Option[ClassHierarchy] =
    val keep = h.bySymbolPath(pathFilter)
    index.info(symbol).map(_.signature).collect { case c: s.ClassSignature =>
      val parents = c.parents.flatMap(h.parentSymbol).filter(keep).map(h.symbolRef).toList
      ClassHierarchy(
        symbol,
        index.displayName(symbol),
        parents,
        h.linearize(symbol).filter(keep).map(h.symbolRef),
        h.knownSubtypes(symbol).filter(keep).map(h.symbolRef)
      )
    }

  // --- find-overloads -------------------------------------------------------

  /** All methods sharing the owner and simple name of `symbol` (overloads differ only by the `(+N)`
    * disambiguator in their symbol string). Works given any one of the overloads.
    */
  def findOverloads(symbol: String): OverloadsResult =
    val name = index.displayName(symbol)
    val own = index.owner(symbol)
    val overloads = index.symbols.values
      .collect {
        case si
            if index
              .isMethod(si.symbol) && index.owner(si.symbol) == own && si.displayName == name =>
          si.symbol
      }
      .toList
      .sorted
      .flatMap(methodSignature)
    OverloadsResult(name, overloads)

  // --- trait-vs-local members -----------------------------------------------

  /** Members declared directly on a class/trait versus those inherited from its linearization. An
    * inherited member that is re-declared locally (overridden) is reported only as declared.
    *
    * `pathFilter`, when given, keeps only members whose own definition uri matches the glob (`*` =
    * any chars, substring match) — e.g. to drop members inherited from types outside a module.
    */
  def members(symbol: String, pathFilter: Option[String] = None): Option[MembersResult] =
    val keep = h.bySymbolPath(pathFilter)
    index.info(symbol).map(_.signature).collect { case _: s.ClassSignature =>
      val declared = h.declarationSymbols(symbol).filter(keep).map(h.memberInfo(_, symbol))
      val declaredNames = declared.map(_.displayName).toSet
      val inherited = h
        .linearize(symbol)
        .flatMap(parent => h.declarationSymbols(parent).map(h.memberInfo(_, parent)))
        .filterNot(m => declaredNames.contains(m.displayName))
        .filter(m => keep(m.symbol))
        .distinctBy(_.displayName)
      MembersResult(symbol, index.displayName(symbol), declared, inherited)
    }

  // --- type-at-position -----------------------------------------------------

  /** The most specific symbol whose occurrence range covers the given 0-based position. */
  def typeAtPosition(uri: String, line: Int, character: Int): Option[TypeAtPosition] =
    index
      .document(uri)
      .toSeq
      .flatMap(_.occurrences)
      .filter(occ => occ.range.exists(h.rangeContains(_, line, character)))
      .minByOption(occ => occ.range.map(h.rangeSpan).getOrElse(Int.MaxValue))
      .map { occ =>
        TypeAtPosition(
          h.location(uri, occ.range),
          occ.symbol,
          index.displayName(occ.symbol),
          h.typeString(occ.symbol)
        )
      }

  // --- resolve-implicits ----------------------------------------------------

  /** Implicit/given instances in the index that produce the given type (by symbol). For a `given`
    * object this is a parent it extends; for a `given def` it is the (possibly synthetic) return
    * type's parent. `chosen` is set only when exactly one candidate exists.
    */
  def resolveImplicits(typeSymbol: String): ImplicitResolution =
    val candidates = h.implicitsProducing(typeSymbol).map { si =>
      ImplicitCandidate(
        h.symbolRef(si.symbol),
        h.renderType(h.producedType(si)),
        fromExplicitImport = false
      )
    }
    val chosen = candidates match
      case one :: Nil => Some(one.target)
      case _          => None
    ImplicitResolution(typeSymbol, chosen, candidates)

  // --- trace-implicit-chain -------------------------------------------------

  /** Givens producing `typeSymbol`, plus the implicit dependencies they pull in, walked
    * transitively. Each step records the implicit-parameter types it `dependsOn`.
    */
  def traceImplicitChain(typeSymbol: String): ImplicitChain =
    def loop(
        queue: List[String],
        seenTypes: List[String],
        steps: List[ImplicitChainStep]
    ): List[ImplicitChainStep] =
      queue match
        case Nil => steps
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
    ImplicitChain(typeSymbol, loop(List(typeSymbol), Nil, Nil).distinctBy(_.target.symbol))

  // --- call-graph path-find -------------------------------------------------

  /** Shortest call path `from -> ... -> to`, with the call-site edges that realize it. Empty `path`
    * means `to` is unreachable from `from`.
    */
  def callPath(from: String, to: String): CallGraphPath =
    val adjacency = callGraph
    def bfs(frontier: List[List[String]], seen: Set[String]): List[String] =
      frontier match
        case Nil => Nil
        case path :: rest =>
          val node = path.head
          if node == to then path.reverse
          else
            val nexts = adjacency.getOrElse(node, Nil).map(_._1).filterNot(seen.contains)
            bfs(rest ::: nexts.map(_ :: path), seen ++ nexts)
    val nodes = if from == to then List(from) else bfs(List(List(from)), Set(from))
    val edges = nodes
      .zip(nodes.drop(1))
      .flatMap { (a, b) =>
        adjacency.getOrElse(a, Nil).find(_._1 == b).map { (_, loc) =>
          CallEdge(h.symbolRef(a), h.symbolRef(b), loc)
        }
      }
    CallGraphPath(h.symbolRef(from), h.symbolRef(to), nodes.map(h.symbolRef), edges)

  /** Caller -> list of (callee, call-site) edges, attributing each method reference to the most
    * recent method definition in source order within its document.
    */
  private lazy val callGraph: Map[String, List[(String, Location)]] =
    val edges = index.documents.flatMap { doc =>
      val ordered = doc.occurrences.sortBy(o =>
        (o.range.map(_.startLine).getOrElse(0), o.range.map(_.startCharacter).getOrElse(0))
      )
      ordered
        .foldLeft((Option.empty[String], List.empty[(String, String, Location)])) {
          case ((current, acc), occ) =>
            val isDef = occ.role == s.SymbolOccurrence.Role.DEFINITION
            val method = index.isMethod(occ.symbol)
            if isDef && method then (Some(occ.symbol), acc)
            else if method && current.exists(_ != occ.symbol) then
              (current, (current.getOrElse(""), occ.symbol, h.location(doc.uri, occ.range)) :: acc)
            else (current, acc)
        }
        ._2
        .reverse
    }
    edges.toList.groupMap(_._1)(e => (e._2, e._3))
