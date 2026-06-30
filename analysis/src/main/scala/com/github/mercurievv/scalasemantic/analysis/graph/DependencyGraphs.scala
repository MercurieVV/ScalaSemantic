package com.github.mercurievv.scalasemantic.analysis.graph

import com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics.Graph
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Builds the multi-relational dependency graph for a project from its [[SemanticIndex]].
  *
  * Nodes are in-project types (a `ClassSignature` with a definition occurrence in the index);
  * methods are lifted to their owning type. External types (e.g. `scala/Int#`) are not nodes, so
  * the metrics describe the project's own architecture. One graph per edge dimension, plus a
  * combined overlay.
  */
final class DependencyGraphs(index: SemanticIndex):

  /** Types defined in this project (have a DEFINITION occurrence and a class signature).
    *
    * Only top-level types are included: nested/inner classes (whose owner is another type, not a
    * package) are excluded. Nested classes always carry an implicit reference to their enclosing
    * class, which would create spurious owner↔nested cycles in the dependency graph and corrupt the
    * structural metrics.
    */
  val nodes: Set[String] =
    val defined = index.occurrences.collect {
      case (_, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION => occ.symbol
    }.toSet
    index.symbols.values.iterator.collect {
      case si if defined.contains(si.symbol) && isClass(si) && isTopLevel(si.symbol) => si.symbol
    }.toSet

  /** The four edge dimensions, each a directed graph over [[nodes]]. */
  val dimensions: Map[String, Graph] = Map(
    "extends" -> extendsGraph,
    "memberType" -> memberTypeGraph,
    "call" -> callGraph,
    "implicit" -> implicitGraph
  )

  /** Union of all dimensions: an edge exists if any dimension has it. */
  val combined: Graph =
    nodes.iterator.map(n => n -> dimensions.values.flatMap(_.getOrElse(n, Set.empty)).toSet).toMap

  /** Module of a type: the leading path segment of its definition uri (e.g. `core`, `analysis`). */
  def moduleOf(symbol: String): String =
    definitionUri(symbol).map(_.takeWhile(_ != '/')).filter(_.nonEmpty).getOrElse("<unknown>")

  // --- per-dimension extraction ---------------------------------------------

  /** `extends`: a type → the in-project types it directly extends. */
  private def extendsGraph: Graph =
    nodes.iterator.map { n =>
      n -> parentsOf(n).filter(p => p != n && nodes.contains(p)).toSet
    }.toMap

  /** `memberType`: a type → in-project types referenced in its members' signatures. */
  private def memberTypeGraph: Graph =
    nodes.iterator.map { n =>
      val refs = declarationSymbols(n)
        .flatMap(m => index.info(m).toList)
        .flatMap(mi => signatureTypeRefs(mi.signature))
        .filter(t => t != n && nodes.contains(t))
        .toSet
      n -> refs
    }.toMap

  /** `call`: method-call edges (attributing references to the enclosing method definition), lifted
    * to the owning types of caller and callee.
    */
  private def callGraph: Graph =
    val typeEdges = index.documents.iterator.flatMap { doc =>
      val ordered = doc.occurrences.sortBy(o =>
        (o.range.map(_.startLine).getOrElse(0), o.range.map(_.startCharacter).getOrElse(0))
      )
      ordered
        .foldLeft((Option.empty[String], List.empty[(String, String)])) {
          case ((_, acc), occ)
              if occ.role == s.SymbolOccurrence.Role.DEFINITION && index.isMethod(occ.symbol) =>
            (Some(occ.symbol), acc)
          case ((Some(current), acc), occ) if index.isMethod(occ.symbol) && current != occ.symbol =>
            (Some(current), (current, occ.symbol) :: acc)
          case (state, _) =>
            state
        }
        ._2
    }
    typeEdges.toList
      .flatMap { (caller, callee) =>
        val from = index.owner(caller)
        val to = index.owner(callee)
        Option.when(from != to && nodes.contains(from) && nodes.contains(to))(from -> to)
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** `implicit`: a type that declares a given/implicit → the in-project types that given pulls in
    * through its implicit parameters.
    */
  private def implicitGraph: Graph =
    val edges = for
      si <- index.symbols.values.toList
      if isImplicit(si)
      owner = if nodes.contains(si.symbol) then si.symbol else index.owner(si.symbol)
      if nodes.contains(owner)
      t <- implicitDependencyTypes(si)
      if t != owner && nodes.contains(t)
    yield owner -> t
    edges.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  // --- semanticdb helpers (local, to keep this module self-contained) -------

  private def isClass(si: s.SymbolInformation): Boolean =
    si.signature match
      case _: s.ClassSignature => true
      case _                   => false

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
    index.occurrences.collectFirst {
      case (uri, occ) if occ.symbol == symbol && occ.role == s.SymbolOccurrence.Role.DEFINITION =>
        uri
    }
