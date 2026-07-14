package com.github.mercurievv.scalasemantic.analysis.graph

import com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics.Graph
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Builds the multi-relational dependency graph for a project from its [[SemanticIndex]].
  *
  * Nodes are in-project structural types: class/object-like definitions plus type definitions
  * (`TypeSignature`, including aliases and opaque types). Methods are lifted to their owning type.
  * External types (e.g. `scala/Int#`) are not nodes, so the metrics describe the project's own
  * architecture. One graph per edge dimension, plus a combined overlay.
  */
final class DependencyGraphs(index: SemanticIndex):

  /** Types defined in this project (have a DEFINITION occurrence and a structural type signature).
    *
    * Nested/inner classes are excluded: they always carry an implicit reference to their enclosing
    * class, which would create spurious owner↔nested cycles in the dependency graph and corrupt the
    * structural metrics. Type definitions are also kept when they are members of a top-level owner,
    * so aliases and opaque types inside an object still participate in the dependency graph.
    */
  val nodes: Set[String] =
    val defined = index.occurrences.collect {
      case (_, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION => occ.symbol
    }.toSet
    index.symbols.values.iterator.collect {
      case si if defined.contains(si.symbol) && includeNode(si) => si.symbol
    }.toSet

  private val dimensionObservations: Map[String, List[(String, String)]] = Map(
    "extends" -> extendsObservations,
    "typeRef" -> typeRefObservations,
    "memberType" -> memberTypeObservations,
    "call" -> callObservations,
    "implicit" -> implicitObservations
  )

  /** The five edge dimensions, each a directed graph over [[nodes]]. */
  val dimensions: Map[String, Graph] =
    dimensionObservations.view.mapValues(observationsToGraph).toMap

  /** Number of raw semantic dependency observations collapsed into each symbol edge. */
  val observationCounts: Map[(String, String), Int] =
    dimensionObservations.values.flatten.toList.groupBy(identity).view.mapValues(_.size).toMap

  /** Union of all dimensions: an edge exists if any dimension has it. */
  val combined: Graph =
    nodes.iterator.map(n => n -> dimensions.values.flatMap(_.getOrElse(n, Set.empty)).toSet).toMap

  /** Module of a type: the leading path segment of its definition uri (e.g. `core`, `analysis`). */
  def moduleOf(symbol: String): String =
    definitionUri(symbol).map(_.takeWhile(_ != '/')).filter(_.nonEmpty).getOrElse("<unknown>")

  // --- per-dimension extraction ---------------------------------------------

  /** `extends`: a type → the in-project types it directly extends. */
  private def extendsObservations: List[(String, String)] =
    nodes.toList.flatMap { n =>
      parentsOf(n).collect { case p if p != n && nodes.contains(p) => n -> p }
    }

  /** `typeRef`: a type definition → in-project types referenced by its RHS/bounds. This captures
    * aliases, opaque types, abstract type bounds, and type members.
    */
  private def typeRefObservations: List[(String, String)] =
    nodes.toList.flatMap { n =>
      (index.info(n).toList.flatMap(si => typeDefinitionRefs(si.signature)) ++
        typeDefinitionOccurrenceRefs.getOrElse(n, Nil))
        .collect { case t if t != n && nodes.contains(t) => n -> t }
    }

  /** `memberType`: a type → in-project types referenced in its members' signatures. */
  private def memberTypeObservations: List[(String, String)] =
    nodes.toList.flatMap { n =>
      declarationSymbols(n)
        .flatMap(m => index.info(m).toList)
        .flatMap(mi => signatureTypeRefs(mi.signature))
        .collect { case t if t != n && nodes.contains(t) => n -> t }
    }

  /** `call`: method-call edges (attributing references to the enclosing method definition), lifted
    * to the owning types of caller and callee.
    */
  private def callObservations: List[(String, String)] =
    val typeEdges = index.documents.iterator.flatMap { doc =>
      val ordered = doc.occurrences.sortBy(o =>
        (o.range.map(_.startLine).getOrElse(0), o.range.map(_.startCharacter).getOrElse(0))
      )
      ordered
        .foldLeft((Option.empty[String], List.empty[(String, String)])):
          case ((_, acc), occ)
              if occ.role == s.SymbolOccurrence.Role.DEFINITION && index.isMethod(occ.symbol) =>
            (Some(occ.symbol), acc)
          case ((Some(current), acc), occ) if index.isMethod(occ.symbol) && current != occ.symbol =>
            (Some(current), (current, occ.symbol) :: acc)
          case (state, _) =>
            state
        ._2
    }
    typeEdges.toList
      .flatMap { (caller, callee) =>
        val from = index.owner(caller)
        val to = index.owner(callee)
        Option.when(from != to && nodes.contains(from) && nodes.contains(to))(from -> to)
      }

  /** `implicit`: a type that declares a given/implicit → the in-project types that given pulls in
    * through its implicit parameters.
    */
  private def implicitObservations: List[(String, String)] =
    for
      si <- index.symbols.values.toList
      if isImplicit(si)
      owner = if nodes.contains(si.symbol) then si.symbol else index.owner(si.symbol)
      if nodes.contains(owner)
      t <- implicitDependencyTypes(si)
      if t != owner && nodes.contains(t)
    yield owner -> t

  private def observationsToGraph(edges: List[(String, String)]): Graph =
    edges.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  // --- semanticdb helpers (local, to keep this module self-contained) -------

  private def includeNode(si: s.SymbolInformation): Boolean =
    si.signature match
      case _: s.ClassSignature => isTopLevel(si.symbol)
      case _: s.TypeSignature  =>
        si.kind == s.SymbolInformation.Kind.TYPE &&
        (isTopLevel(si.symbol) || isTopLevel(index.owner(si.symbol)))
      case _ => false

  /** Returns true if the symbol is a top-level type (its owner is a package, not another type).
    * Inner/nested classes whose owner is a type always form a synthetic owner↔nested cycle (the
    * nested class implicitly references the outer), so they must be excluded from the node set.
    */
  private def isTopLevel(symbol: String): Boolean =
    val owner = index.owner(symbol)
    owner.isEmpty || owner.endsWith("/")

  private def parentsOf(symbol: String): List[String] =
    index.info(symbol).toList.flatMap { si =>
      si.signature match
        case c: s.ClassSignature => c.parents.flatMap(typeHead)
        case _                   => Nil
    }

  private def declarationSymbols(symbol: String): List[String] =
    index
      .info(symbol)
      .flatMap(_.signature match
        case c: s.ClassSignature => Some(scopeInfos(c.declarations).map(_.symbol).toList)
        case _                   => None)
      .getOrElse(Nil)

  /** All in-project type symbols referenced by a member's signature (return/value type, parameter
    * types) — including type arguments (`List[Foo]` references both `List` and `Foo`).
    */
  private def signatureTypeRefs(sig: s.Signature): Set[String] =
    sig match
      case v: s.ValueSignature  => typeRefs(v.tpe)
      case m: s.MethodSignature =>
        typeRefs(m.returnType) ++
          m.parameterLists.flatMap(pl => scopeInfos(Some(pl))).flatMap(p => typeRefs(valueType(p)))
      case _ => Set.empty

  private def typeDefinitionRefs(sig: s.Signature): Set[String] =
    sig match
      case t: s.TypeSignature =>
        typeRefs(t.lowerBound) ++ typeRefs(t.upperBound) ++
          t.typeParameters.toList
            .flatMap(tp => scopeInfos(Some(tp)))
            .flatMap(p => typeDefinitionRefs(p.signature))
      case _ => Set.empty

  /** SemanticDB's `TypeSignature` records bounds, but alias RHS references are also available as
    * occurrences in the source region after the type definition name. Capture those references up
    * to the next definition so aliases and opaque types contribute real dependency edges.
    */
  private lazy val typeDefinitionOccurrenceRefs: Map[String, List[String]] =
    index.documents.iterator.flatMap { doc =>
      val ordered = doc.occurrences.sortBy(o =>
        (o.range.map(_.startLine).getOrElse(0), o.range.map(_.startCharacter).getOrElse(0))
      )
      ordered.zipWithIndex.collect {
        case (occ, idx)
            if occ.role == s.SymbolOccurrence.Role.DEFINITION &&
              nodes.contains(occ.symbol) &&
              index
                .info(occ.symbol)
                .exists(_.signature match
                  case _: s.TypeSignature => true
                  case _ => false) =>
          val refs = ordered
            .drop(idx + 1)
            .takeWhile(_.role != s.SymbolOccurrence.Role.DEFINITION)
            .filter(_.role == s.SymbolOccurrence.Role.REFERENCE)
            .map(_.symbol)
            .filter(nodes.contains)
            .toList
          occ.symbol -> refs
      }
    }.toMap

  private def implicitDependencyTypes(si: s.SymbolInformation): Set[String] =
    si.signature match
      case m: s.MethodSignature =>
        m.parameterLists.toList
          .flatMap(pl => scopeInfos(Some(pl)))
          .filter(isImplicit)
          .flatMap(p => typeRefs(valueType(p)))
          .toSet
      case _ => Set.empty

  /** Type symbols a `Type` references, head plus all type arguments, recursively. */
  private def typeRefs(t: s.Type): Set[String] =
    t match
      case s.TypeRef(_, sym, args)  => args.flatMap(typeRefs).toSet + sym
      case s.SingleType(_, sym)     => Set(sym)
      case s.ThisType(sym)          => Set(sym)
      case s.SuperType(_, sym)      => Set(sym)
      case s.ByNameType(inner)      => typeRefs(inner)
      case s.RepeatedType(inner)    => typeRefs(inner)
      case s.WithType(ts)           => ts.flatMap(typeRefs).toSet
      case s.IntersectionType(ts)   => ts.flatMap(typeRefs).toSet
      case s.UnionType(ts)          => ts.flatMap(typeRefs).toSet
      case s.AnnotatedType(_, t2)   => typeRefs(t2)
      case s.ExistentialType(t2, _) => typeRefs(t2)
      case s.UniversalType(_, t2)   => typeRefs(t2)
      case s.StructuralType(t2, _)  => typeRefs(t2)
      case _                        => Set.empty

  private def typeHead(t: s.Type): Option[String] =
    t match
      case s.TypeRef(_, sym, _) => Some(sym)
      case s.SingleType(_, sym) => Some(sym)
      case _                    => None

  private def scopeInfos(scope: Option[s.Scope]): Seq[s.SymbolInformation] =
    scope.toSeq.flatMap { sc =>
      if sc.hardlinks.nonEmpty then sc.hardlinks else sc.symlinks.flatMap(index.info)
    }

  private def valueType(info: s.SymbolInformation): s.Type =
    info.signature match
      case v: s.ValueSignature  => v.tpe
      case m: s.MethodSignature => m.returnType
      case _                    => s.Type.Empty

  private def isImplicit(info: s.SymbolInformation): Boolean =
    (info.properties & s.SymbolInformation.Property.IMPLICIT.value) != 0

  private def definitionUri(symbol: String): Option[String] =
    index.occurrences.collectFirst:
      case (uri, occ) if occ.symbol == symbol && occ.role == s.SymbolOccurrence.Role.DEFINITION =>
        uri
