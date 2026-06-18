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

// --- call-graph path-find ---------------------------------------------------

case class CallEdge(from: SymbolRef, to: SymbolRef, at: Location) derives ReadWriter

case class CallGraphPath(
    from: SymbolRef,
    to: SymbolRef,
    path: List[SymbolRef],
    edges: List[CallEdge]
) derives ReadWriter
