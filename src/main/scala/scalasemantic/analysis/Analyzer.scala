package scalasemantic.analysis

import scalasemantic.model.*
import scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** The semantic query engine: turns a [[SemanticIndex]] into the result models that back the MCP
  * tools. Phase 3 covers find-usages, method-signature, and class-hierarchy.
  */
final class Analyzer(index: SemanticIndex):

  // --- find-usages ----------------------------------------------------------

  /** Every occurrence of `symbol` across all indexed documents, split into definitions and
    * references. This is inherently cross-file: occurrences are scanned over the whole index.
    */
  def findUsages(symbol: String): UsagesResult =
    val located = index.occurrences.collect {
      case (uri, occ) if occ.symbol == symbol =>
        occ.role -> location(uri, occ.range)
    }
    val defs = located.collect { case (s.SymbolOccurrence.Role.DEFINITION, loc) => loc }.toList
    val refs = located.collect { case (s.SymbolOccurrence.Role.REFERENCE, loc) => loc }.toList
    UsagesResult(symbol, index.displayName(symbol), defs, refs)

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
    */
  def classHierarchy(symbol: String): Option[ClassHierarchy] =
    index.info(symbol).map(_.signature).collect { case c: s.ClassSignature =>
      val parents = c.parents.flatMap(parentSymbol).map(symbolRef).toList
      ClassHierarchy(
        symbol,
        index.displayName(symbol),
        parents,
        linearize(symbol).map(symbolRef),
        knownSubtypes(symbol).map(symbolRef)
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
    */
  def members(symbol: String): Option[MembersResult] =
    index.info(symbol).map(_.signature).collect { case _: s.ClassSignature =>
      val declared = declarationSymbols(symbol).map(memberInfo(_, symbol))
      val declaredNames = declared.map(_.displayName).toSet
      val inherited = linearize(symbol)
        .flatMap(parent => declarationSymbols(parent).map(memberInfo(_, parent)))
        .filterNot(m => declaredNames.contains(m.displayName))
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
      case _                       => ""
