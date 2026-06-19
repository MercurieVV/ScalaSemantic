package com.github.mercurievv.scalasemantic.model

import upickle.default.ReadWriter

/** Wire models returned by the analysis engine and serialized as the MCP tool results.
  *
  * Everything derives upickle `ReadWriter` so a result is one `write(value)` away from JSON.
  * Positions are 0-based (LSP / SemanticDB convention): `endCharacter` is exclusive.
  */

/** A point in a source file. */
case class Position(line: Int, character: Int) derives ReadWriter

/** A half-open `[start, end)` span. */
case class Range(start: Position, end: Position) derives ReadWriter

/** A span within a specific document. */
case class Location(uri: String, range: Range) derives ReadWriter

/** A reference to a symbol: its SemanticDB string, human name, and kind (e.g. CLASS, METHOD). */
case class SymbolRef(symbol: String, displayName: String, kind: String) derives ReadWriter

// --- find-usages ------------------------------------------------------------

case class UsagesResult(
    symbol: String,
    displayName: String,
    definitions: List[Location],
    references: List[Location]
) derives ReadWriter

// --- method-signature / find-overloads --------------------------------------

case class Parameter(name: String, tpe: String, isImplicit: Boolean) derives ReadWriter

case class ParameterList(parameters: List[Parameter], isImplicit: Boolean) derives ReadWriter

case class MethodSignature(
    symbol: String,
    displayName: String,
    typeParameters: List[String],
    parameterLists: List[ParameterList],
    returnType: String,
    rendered: String
) derives ReadWriter

case class OverloadsResult(name: String, overloads: List[MethodSignature]) derives ReadWriter

// --- class-hierarchy --------------------------------------------------------

case class ClassHierarchy(
    symbol: String,
    displayName: String,
    parents: List[SymbolRef],
    linearization: List[SymbolRef],
    knownSubtypes: List[SymbolRef]
) derives ReadWriter

// --- trait-vs-local members -------------------------------------------------

case class MemberInfo(
    symbol: String,
    displayName: String,
    kind: String,
    declaredIn: SymbolRef
) derives ReadWriter

case class MembersResult(
    symbol: String,
    displayName: String,
    declared: List[MemberInfo],
    inherited: List[MemberInfo]
) derives ReadWriter

// --- resolve-implicits / trace-implicit-chain -------------------------------

case class ImplicitCandidate(
    target: SymbolRef,
    tpe: String,
    fromExplicitImport: Boolean
) derives ReadWriter

case class ImplicitResolution(
    queryType: String,
    chosen: Option[SymbolRef],
    candidates: List[ImplicitCandidate]
) derives ReadWriter

case class ImplicitChainStep(
    target: SymbolRef,
    tpe: String,
    dependsOn: List[String]
) derives ReadWriter

case class ImplicitChain(queryType: String, steps: List[ImplicitChainStep]) derives ReadWriter

// --- type-at-position -------------------------------------------------------

case class TypeAtPosition(
    location: Location,
    symbol: String,
    displayName: String,
    tpe: String
) derives ReadWriter

// --- structure / dependency metrics -----------------------------------------

/** Coupling metrics for one node in one edge dimension (or the combined overlay).
  *
  * `afferent` (Ca) = incoming deps (fan-in); `efferent` (Ce) = outgoing deps (fan-out);
  * `instability` = Ce/(Ca+Ce) in [0,1] — 0 = stable foundation (depended-on, depends on little), 1
  * \= unstable leaf/entry. `layer` = longest dependency-chain depth (0 = foundation; higher = sits
  * on deeper chains), computed on the SCC-condensed graph. `inCycle` is true when the node sits in
  * a non-trivial strongly-connected component (`sccSize` > 1); then `layer` is the cycle's level as
  * a whole and the order *within* the cycle is undefined — cyclic membership is reported, never
  * hidden behind a faked per-node layer.
  */
case class DimensionMetrics(
    afferent: Int,
    efferent: Int,
    instability: Double,
    layer: Int,
    centrality: Double,
    sccSize: Int,
    inCycle: Boolean
) derives ReadWriter

/** Structural metrics for one in-project type, in the combined graph and per edge dimension
  * (`extends`, `memberType`, `call`, `implicit`).
  */
case class SymbolStructure(
    symbol: String,
    displayName: String,
    module: String,
    combined: DimensionMetrics,
    perDimension: Map[String, DimensionMetrics]
) derives ReadWriter

/** A dependency cycle: the members of one non-trivial strongly-connected component in a dimension.
  */
case class DependencyCycle(dimension: String, members: List[String]) derives ReadWriter

/** Module-level rollup of the combined graph (module = leading path segment of a type's uri). */
case class ModuleStructure(
    module: String,
    typeCount: Int,
    afferent: Int,
    efferent: Int,
    instability: Double,
    layer: Int,
    sccSize: Int,
    inCycle: Boolean
) derives ReadWriter

/** A directed dependency edge between two modules in the combined graph: `weight` = how many type
  * edges cross from `from` to `to`; `inCycle` marks an edge whose endpoints share a module cycle (a
  * mutual-dependency boundary violation).
  */
case class ModuleEdge(from: String, to: String, weight: Int, inCycle: Boolean) derives ReadWriter

case class StructureResult(
    symbols: List[SymbolStructure],
    modules: List[ModuleStructure],
    moduleEdges: List[ModuleEdge],
    cycles: List[DependencyCycle]
) derives ReadWriter

// --- call-graph path-find ---------------------------------------------------

case class CallEdge(from: SymbolRef, to: SymbolRef, at: Location) derives ReadWriter

case class CallGraphPath(
    from: SymbolRef,
    to: SymbolRef,
    path: List[SymbolRef],
    edges: List[CallEdge]
) derives ReadWriter
