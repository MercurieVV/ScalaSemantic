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
  */
final class Analyzer(
    index: SemanticIndex,
    pc: Option[PresentationCompilerBackend] = None
):

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
              if occ.role == s.SymbolOccurrence.Role.DEFINITION && includeInOutline(occ.symbol) =>
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
          kindName(sym),
          line,
          outlineSignature(sym),
          kids.map((c, l) => build(c, l))
        )
      defs.filter((sym, _) => parentOf(sym).isEmpty).sortBy(_._2).map((sym, l) => build(sym, l))
    }

  private val outlineKinds: Set[s.SymbolInformation.Kind] = Set(
    s.SymbolInformation.Kind.CLASS,
    s.SymbolInformation.Kind.TRAIT,
    s.SymbolInformation.Kind.INTERFACE,
    s.SymbolInformation.Kind.OBJECT,
    s.SymbolInformation.Kind.METHOD,
    s.SymbolInformation.Kind.MACRO,
    s.SymbolInformation.Kind.TYPE,
    s.SymbolInformation.Kind.FIELD
  )

  private def includeInOutline(symbol: String): Boolean =
    index.info(symbol).exists { si =>
      outlineKinds.contains(si.kind) && si.displayName.nonEmpty && si.displayName != "<init>"
    }

  /** A one-line signature for an outline entry, rendered from SemanticDB: a method's full clarified
    * signature, a value's resolved type, or empty for a type (its name + kind already say enough).
    */
  private def outlineSignature(symbol: String): String =
    index.info(symbol).map(_.signature) match
      case Some(_: s.MethodSignature) => methodSignature(symbol).map(_.rendered).getOrElse("")
      case Some(v: s.ValueSignature)  => s": ${renderType(v.tpe)}"
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
      val synthetic = doc.synthetics.iterator.flatMap(syntheticAnnotation).toList
      val defTypes = doc.occurrences.iterator.flatMap(defTypeAnnotation(_, sourceLines)).toList
      (synthetic ++ defTypes).distinct.sortBy(a => (a.line, a.character, a.kind))
    }

  /** An implicit-insertion or inferred-type-argument annotation for one synthetic, anchored at the
    * synthetic's source range. The `kind` distinguishes notes whose range is a precise call site
    * (`inferred-type-args`, `implicit-conversion`) from the using-argument note, whose range is the
    * zero-width enclosing point and so carries no trustworthy column. `None` for synthetics we do
    * not surface.
    */
  private def syntheticAnnotation(syn: s.Synthetic): Option[SourceAnnotation] =
    val r = syn.range.getOrElse(s.Range.defaultInstance)
    syn.tree match
      case t: s.TypeApplyTree if t.typeArguments.nonEmpty =>
        Some(
          SourceAnnotation(
            r.startLine,
            r.startCharacter,
            "inferred-type-args",
            t.typeArguments.map(renderType).mkString("[", ", ", "]")
          )
        )
      case app: s.ApplyTree =>
        app.function match
          // using-args appended to a visible call: the function IS the original expression, so the
          // range is the enclosing point, not where the args belong — no reliable column.
          case _: s.OriginalTree =>
            val args = app.arguments.iterator.flatMap(insertedName).toList
            Option.when(args.nonEmpty)(
              SourceAnnotation(
                r.startLine,
                r.startCharacter,
                "implicit",
                args.mkString("(using ", ", ", ")")
              )
            )
          // an implicit conversion wraps the original expression: the range pins the converted
          // expression, so the column is meaningful.
          case fn =>
            insertedName(fn).map(c =>
              SourceAnnotation(r.startLine, r.startCharacter, "implicit-conversion", s"$c(…)")
            )
      case _ => None

  /** Best-effort display name of an inserted implicit tree (a given/implicit reference). */
  private def insertedName(tree: s.Tree): Option[String] =
    tree match
      case t: s.IdTree        => Some(index.displayName(t.symbol))
      case t: s.SelectTree    => t.id.flatMap(insertedName)
      case t: s.TypeApplyTree => insertedName(t.function)
      case t: s.ApplyTree     => insertedName(t.function)
      case _                  => None

  private val annotatedDefKinds: Set[s.SymbolInformation.Kind] =
    Set(s.SymbolInformation.Kind.METHOD, s.SymbolInformation.Kind.FIELD)

  /** The inferred result/value type of a definition occurrence, as a `: T` note — but only when the
    * source line did not already ascribe a type (so explicitly-typed definitions stay un-noted).
    */
  private def defTypeAnnotation(
      occ: s.SymbolOccurrence,
      sourceLines: IndexedSeq[String]
  ): Option[SourceAnnotation] =
    if occ.role != s.SymbolOccurrence.Role.DEFINITION then None
    else
      index
        .info(occ.symbol)
        .filter(si => annotatedDefKinds.contains(si.kind) && si.displayName != "<init>")
        .flatMap { si =>
          val tpe = si.signature match
            case m: s.MethodSignature => m.returnType
            case v: s.ValueSignature  => v.tpe
            case _                    => s.Type.Empty
          val rendered = renderType(tpe)
          val r = occ.range.getOrElse(s.Range.defaultInstance)
          val line = sourceLines.lift(r.startLine).getOrElse("")
          if rendered.isEmpty || alreadyAscribed(line, r.startCharacter) then None
          else
            Some(SourceAnnotation(r.startLine, r.startCharacter, "inferred-type", s": $rendered"))
        }

  /** Whether the definition whose name starts at `nameStart` on `line` already has an explicit type
    * ascription: a top-level `:` (outside any `()`/`[]` group) before the `=` of its body.
    */
  private def alreadyAscribed(line: String, nameStart: Int): Boolean =
    @annotation.tailrec
    def scan(i: Int, depth: Int): Boolean =
      if i >= line.length then false
      else
        line.charAt(i) match
          case '=' if depth == 0 => false
          case ':' if depth == 0 => true
          case '(' | '['         => scan(i + 1, depth + 1)
          case ')' | ']'         => scan(i + 1, depth - 1)
          case _                 => scan(i + 1, depth)
    nameStart >= 0 && nameStart < line.length && scan(nameStart, 0)

  // --- rename plan ----------------------------------------------------------

  /** The precise edits to rename `symbol` to `newName`: every compiler-resolved occurrence of its
    * name (definitions and references), as `{uri, range, old→new}`. Because the occurrences come
    * from SemanticDB, this never touches comments, strings, or unrelated same-named identifiers —
    * the over-match a textual rename suffers. The MCP server is read-only, so this returns a plan
    * for the caller to apply.
    */
  def renamePlan(symbol: String, newName: String): RenamePlan =
    val oldName = index.displayName(symbol)
    val edits = index.occurrences
      .collect {
        case (uri, occ) if occ.symbol == symbol =>
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
    val keepPath = bySymbolPath(pathFilter)
    val excludedKinds = Set[s.SymbolInformation.Kind](
      s.SymbolInformation.Kind.PARAMETER,
      s.SymbolInformation.Kind.TYPE_PARAMETER,
      s.SymbolInformation.Kind.SELF_PARAMETER
    )
    index.symbols.values.iterator
      .filter(si => index.isGlobal(si.symbol))
      .filter(si => !excludedKinds.contains(si.kind))
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
      .map(si => symbolRef(si.symbol))

  // --- find-usages ----------------------------------------------------------

  /** Every occurrence of `symbol` across all indexed documents, split into definitions and
    * references. This is inherently cross-file: occurrences are scanned over the whole index.
    *
    * `pathFilter`, when given, keeps only occurrences whose document uri matches the glob (`*` =
    * any chars, unanchored substring match) — e.g. "core" + star, or star + "compat" + star.
    * `referenceCount` reflects the filtered set.
    */
  def findUsages(symbol: String, pathFilter: Option[String] = None): UsagesResult =
    val keep = globMatcher(pathFilter)
    val located = index.occurrences.collect {
      case (uri, occ) if occ.symbol == symbol && keep(uri) =>
        occ.role -> location(uri, occ.range)
    }
    val defs =
      located.collect { case (s.SymbolOccurrence.Role.DEFINITION, loc) => loc }.distinct.toList
    val refs =
      located.collect { case (s.SymbolOccurrence.Role.REFERENCE, loc) => loc }.distinct.toList
    UsagesResult(symbol, index.displayName(symbol), defs, refs)

  /** A predicate from an optional glob: `*` matches any run of chars, the rest is literal, matched
    * unanchored (substring). `None` keeps everything.
    */
  private def globMatcher(pattern: Option[String]): String => Boolean =
    pattern match
      case None => _ => true
      case Some(glob) =>
        val regex = glob.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*").r
        uri => regex.findFirstIn(uri).isDefined

  /** A symbol-level predicate: keep a symbol when its definition uri matches the glob. A symbol
    * with no definition occurrence in the index (e.g. external types) is dropped only when a filter
    * is given. `None` keeps everything.
    */
  private def bySymbolPath(pattern: Option[String]): String => Boolean =
    pattern match
      case None => _ => true
      case Some(_) =>
        val keepUri = globMatcher(pattern)
        sym => definitionUri(sym).exists(keepUri)

  /** The document uri of a symbol's definition occurrence, if the index has one. */
  private def definitionUri(symbol: String): Option[String] =
    index.occurrences.collectFirst {
      case (uri, occ) if occ.symbol == symbol && occ.role == s.SymbolOccurrence.Role.DEFINITION =>
        uri
    }

  // --- method-signature -----------------------------------------------------

  /** Full method signature including type parameters and (implicit) parameter lists. */
  def methodSignature(symbol: String): Option[MethodSignature] =
    index.info(symbol).map(_.signature).collect { case m: s.MethodSignature =>
      val name = index.displayName(symbol)
      val tparams = scopeInfos(m.typeParameters).map(_.displayName).toList
      val plists = m.parameterLists.map { scope =>
        val params = scopeInfos(Some(scope)).map { p =>
          Parameter(p.displayName, renderType(valueType(p)), isImplicit(p))
        }.toList
        ParameterList(params, params.nonEmpty && params.forall(_.isImplicit))
      }.toList
      val ret = renderType(m.returnType)
      MethodSignature(symbol, name, tparams, plists, ret, renderMethod(name, tparams, plists, ret))
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
    val keep = bySymbolPath(pathFilter)
    index.info(symbol).map(_.signature).collect { case c: s.ClassSignature =>
      val parents = c.parents.flatMap(parentSymbol).filter(keep).map(symbolRef).toList
      ClassHierarchy(
        symbol,
        index.displayName(symbol),
        parents,
        linearize(symbol).filter(keep).map(symbolRef),
        knownSubtypes(symbol).filter(keep).map(symbolRef)
      )
    }

  /** Direct parent symbols declared by a type's `ClassSignature` (empty for non-classes). */
  private def directParents(info: s.SymbolInformation): List[String] =
    info.signature match
      case c: s.ClassSignature => c.parents.flatMap(parentSymbol).toList
      case _                   => Nil

  /** Depth-first transitive parents (excluding `symbol` itself), de-duplicated by first sight. */
  private def linearize(symbol: String): List[String] =
    def parentsOf(sym: String): List[String] = index.info(sym).map(directParents).getOrElse(Nil)
    def loop(queue: List[String], seen: List[String]): List[String] =
      queue match
        case Nil => seen
        case head :: tail =>
          if seen.contains(head) then loop(tail, seen)
          else loop(parentsOf(head) ::: tail, seen :+ head)
    loop(parentsOf(symbol), Nil)

  /** All indexed classes/traits that declare `symbol` among their direct parents. */
  private def knownSubtypes(symbol: String): List[String] =
    index.symbols.values
      .collect {
        case si if directParents(si).contains(symbol) => si.symbol
      }
      .toList
      .sorted

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
    val keep = bySymbolPath(pathFilter)
    index.info(symbol).map(_.signature).collect { case _: s.ClassSignature =>
      val declared = declarationSymbols(symbol).filter(keep).map(memberInfo(_, symbol))
      val declaredNames = declared.map(_.displayName).toSet
      val inherited = linearize(symbol)
        .flatMap(parent => declarationSymbols(parent).map(memberInfo(_, parent)))
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
      .filter(occ => occ.range.exists(rangeContains(_, line, character)))
      .minByOption(occ => occ.range.map(rangeSpan).getOrElse(Int.MaxValue))
      .map { occ =>
        TypeAtPosition(
          location(uri, occ.range),
          occ.symbol,
          index.displayName(occ.symbol),
          typeString(occ.symbol)
        )
      }

  // --- resolve-implicits ----------------------------------------------------

  /** Implicit/given instances in the index that produce the given type (by symbol). For a `given`
    * object this is a parent it extends; for a `given def` it is the (possibly synthetic) return
    * type's parent. `chosen` is set only when exactly one candidate exists.
    */
  def resolveImplicits(typeSymbol: String): ImplicitResolution =
    val candidates = implicitsProducing(typeSymbol).map { si =>
      ImplicitCandidate(
        symbolRef(si.symbol),
        renderType(producedType(si)),
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
            val produced = implicitsProducing(tpe)
            val newSteps = produced.map { si =>
              val deps = implicitDependencyHeads(si)
              ImplicitChainStep(symbolRef(si.symbol), renderType(producedType(si)), deps)
            }
            val nextTypes = produced.flatMap(implicitDependencyHeads)
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
          CallEdge(symbolRef(a), symbolRef(b), loc)
        }
      }
    CallGraphPath(symbolRef(from), symbolRef(to), nodes.map(symbolRef), edges)

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
              (current, acc :+ ((current.getOrElse(""), occ.symbol, location(doc.uri, occ.range))))
            else (current, acc)
        }
        ._2
    }
    edges.toList.groupMap(_._1)(e => (e._2, e._3))

  /** Given/implicit *definitions* (a given object or def/val) whose produced type's head is
    * `typeSymbol`. Excludes implicit parameters and the synthetic self-class a `given ... with`
    * emits (whose members are owned by an implicit type).
    */
  private def implicitsProducing(typeSymbol: String): List[s.SymbolInformation] =
    index.symbols.values
      .collect {
        case si if isGivenDefinition(si) && parentSymbol(producedType(si)).contains(typeSymbol) =>
          si
      }
      .toList
      .sortBy(_.symbol)

  private def isGivenDefinition(info: s.SymbolInformation): Boolean =
    val k = info.kind
    isImplicit(info) &&
    (k == s.SymbolInformation.Kind.OBJECT || k == s.SymbolInformation.Kind.METHOD) &&
    !index.info(index.owner(info.symbol)).exists(isImplicit)

  /** The type an implicit instance provides: a given object's first non-Object parent, or a given
    * def/val's result type (unwrapping the synthetic self-class a `given ... with` emits).
    */
  private def producedType(info: s.SymbolInformation): s.Type =
    info.signature match
      case c: s.ClassSignature  => c.parents.find(notObject).getOrElse(s.Type.Empty)
      case m: s.MethodSignature => unwrapSelfClass(m.returnType, info.displayName)
      case v: s.ValueSignature  => unwrapSelfClass(v.tpe, info.displayName)
      case _                    => s.Type.Empty

  /** If `tpe`'s head is the synthetic class named after the given (`given x ... with`), replace it
    * with the interface that class extends; otherwise return `tpe` unchanged.
    */
  private def unwrapSelfClass(tpe: s.Type, givenName: String): s.Type =
    val isSelfClass = parentSymbol(tpe).exists(sym => index.displayName(sym) == givenName)
    if !isSelfClass then tpe
    else
      parentSymbol(tpe)
        .flatMap(index.info)
        .map(_.signature)
        .collect { case c: s.ClassSignature => c.parents.find(notObject) }
        .flatten
        .getOrElse(tpe)

  private def notObject(tpe: s.Type): Boolean =
    !parentSymbol(tpe).contains("java/lang/Object#")

  /** Head symbols of an implicit method's implicit-parameter types (its dependencies). */
  private def implicitDependencyHeads(info: s.SymbolInformation): List[String] =
    info.signature match
      case m: s.MethodSignature =>
        m.parameterLists.toList
          .flatMap(scope => scopeInfos(Some(scope)))
          .filter(isImplicit)
          .flatMap(p => parentSymbol(valueType(p)))
      case _ => Nil

  // --- shared helpers -------------------------------------------------------

  /** Member symbols declared in a type's `ClassSignature.declarations` scope. */
  private def declarationSymbols(symbol: String): List[String] =
    index
      .info(symbol)
      .map(_.signature)
      .collect { case c: s.ClassSignature => scopeInfos(c.declarations).map(_.symbol).toList }
      .getOrElse(Nil)

  private def memberInfo(member: String, declaredIn: String): MemberInfo =
    MemberInfo(member, index.displayName(member), kindName(member), symbolRef(declaredIn))

  private def rangeContains(r: s.Range, line: Int, character: Int): Boolean =
    val afterStart = line > r.startLine || (line == r.startLine && character >= r.startCharacter)
    val beforeEnd = line < r.endLine || (line == r.endLine && character < r.endCharacter)
    afterStart && beforeEnd

  private def rangeSpan(r: s.Range): Int =
    (r.endLine - r.startLine) * 10000 + (r.endCharacter - r.startCharacter)

  /** A symbol's type as text: a method's return, a value's type, else the symbol's own name. */
  private def typeString(symbol: String): String =
    index.info(symbol).map(_.signature) match
      case Some(m: s.MethodSignature) => renderType(m.returnType)
      case Some(v: s.ValueSignature)  => renderType(v.tpe)
      case _                          => index.displayName(symbol)

  private def location(uri: String, range: Option[s.Range]): Location =
    val r = range.getOrElse(s.Range.defaultInstance)
    Location(
      uri,
      Range(Position(r.startLine, r.startCharacter), Position(r.endLine, r.endCharacter))
    )

  private def symbolRef(symbol: String): SymbolRef =
    SymbolRef(symbol, index.displayName(symbol), kindName(symbol))

  private def kindName(symbol: String): String =
    index.info(symbol).map(_.kind.toString).getOrElse("UNKNOWN")

  private def parentSymbol(tpe: s.Type): Option[String] =
    tpe match
      case s.TypeRef(_, sym, _) => Some(sym)
      case s.SingleType(_, sym) => Some(sym)
      case _                    => None

  private def scopeInfos(scope: Option[s.Scope]): Seq[s.SymbolInformation] =
    scope.toSeq.flatMap { sc =>
      if sc.hardlinks.nonEmpty then sc.hardlinks
      else sc.symlinks.flatMap(index.info)
    }

  private def valueType(info: s.SymbolInformation): s.Type =
    info.signature match
      case v: s.ValueSignature  => v.tpe
      case m: s.MethodSignature => m.returnType
      case _                    => s.Type.Empty

  private def isImplicit(info: s.SymbolInformation): Boolean =
    (info.properties & s.SymbolInformation.Property.IMPLICIT.value) != 0

  private def renderMethod(
      name: String,
      tparams: List[String],
      plists: List[ParameterList],
      ret: String
  ): String =
    val tp = if tparams.isEmpty then "" else tparams.mkString("[", ", ", "]")
    val ps = plists.map { pl =>
      val prefix = if pl.isImplicit then "implicit " else ""
      pl.parameters.map(p => s"${p.name}: ${p.tpe}").mkString(s"($prefix", ", ", ")")
    }.mkString
    s"def $name$tp$ps: $ret"

  /** Best-effort rendering of a SemanticDB type to readable Scala-ish text. */
  private def renderType(tpe: s.Type): String =
    tpe match
      case s.TypeRef(_, sym, args) =>
        val base = index.displayName(sym)
        if args.isEmpty then base else args.map(renderType).mkString(s"$base[", ", ", "]")
      case s.SingleType(_, sym)    => s"${index.displayName(sym)}.type"
      case s.ThisType(sym)         => s"${index.displayName(sym)}.this"
      case s.SuperType(_, sym)     => index.displayName(sym)
      case s.ByNameType(t)         => s"=> ${renderType(t)}"
      case s.RepeatedType(t)       => s"${renderType(t)}*"
      case s.WithType(ts)          => ts.map(renderType).mkString(" with ")
      case s.IntersectionType(ts)  => ts.map(renderType).mkString(" & ")
      case s.UnionType(ts)         => ts.map(renderType).mkString(" | ")
      case s.AnnotatedType(_, t)   => renderType(t)
      case s.ExistentialType(t, _) => renderType(t)
      case s.UniversalType(_, t)   => renderType(t)
      case s.StructuralType(t, _)  => renderType(t)
      case s.ConstantType(c)       => renderConstant(c)
      case _                       => ""

  /** Render a literal/constant type (Scala 3 singleton-literal types, e.g. `42`, `"x"`, `true`). */
  private def renderConstant(c: s.Constant): String =
    c match
      case s.IntConstant(v)     => v.toString
      case s.LongConstant(v)    => s"${v}L"
      case s.FloatConstant(v)   => s"${v}f"
      case s.DoubleConstant(v)  => v.toString
      case s.BooleanConstant(v) => v.toString
      case s.CharConstant(v)    => s"'${v.toChar}'"
      case s.StringConstant(v)  => s"\"$v\""
      case s.ShortConstant(v)   => v.toString
      case s.ByteConstant(v)    => v.toString
      case s.UnitConstant()     => "Unit"
      case s.NullConstant()     => "null"
      case _                    => ""
