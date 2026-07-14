package com.github.mercurievv.scalasemantic.model

import upickle.default.ReadWriter
import upickle.default.readwriter

/** Wire models returned by the analysis engine and serialized as the MCP tool results.
  *
  * Everything derives upickle `ReadWriter` so a result is one `write(value)` away from JSON.
  * Positions are 0-based (LSP / SemanticDB convention): `endCharacter` is exclusive.
  */

/** The declaration kind of a symbol, mirroring SemanticDB's `SymbolInformation.Kind` (e.g. CLASS,
  * TRAIT, METHOD). `value` is the SemanticDB enum name and is what serializes, so JSON stays
  * byte-identical to the previous raw-string field. `Unknown` absorbs any kind SemanticDB did not
  * record (including its `UNKNOWN_KIND`).
  */
enum SymbolKind(val value: String):
  case Class extends SymbolKind("CLASS")
  case Trait extends SymbolKind("TRAIT")
  case Interface extends SymbolKind("INTERFACE")
  case Object extends SymbolKind("OBJECT")
  case Method extends SymbolKind("METHOD")
  case Constructor extends SymbolKind("CONSTRUCTOR")
  case Macro extends SymbolKind("MACRO")
  case Type extends SymbolKind("TYPE")
  case Field extends SymbolKind("FIELD")
  case Package extends SymbolKind("PACKAGE")
  case PackageObject extends SymbolKind("PACKAGE_OBJECT")
  case Parameter extends SymbolKind("PARAMETER")
  case SelfParameter extends SymbolKind("SELF_PARAMETER")
  case TypeParameter extends SymbolKind("TYPE_PARAMETER")
  case Local extends SymbolKind("LOCAL")
  case Unknown extends SymbolKind("UNKNOWN")

object SymbolKind:
  /** The kind for a SemanticDB enum name (`_.kind.toString`), falling back to [[Unknown]]. */
  def from(raw: String): SymbolKind = values.find(_.value == raw).getOrElse(Unknown)

  given ReadWriter[SymbolKind] = readwriter[String].bimap(_.value, from)

/** A point in a source file. */
case class Position(line: Int, character: Int) derives ReadWriter

/** A half-open `[start, end)` span. */
case class Range(start: Position, end: Position) derives ReadWriter

/** A span within a specific document. */
case class Location(uri: String, range: Range) derives ReadWriter

/** A reference to a symbol: its SemanticDB string, human name, and kind (e.g. CLASS, METHOD). */
case class SymbolRef(symbol: String, displayName: String, kind: SymbolKind) derives ReadWriter

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
    kind: SymbolKind,
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

case class ImplicitTree(
    targetType: String,
    chosen: Option[SymbolRef],
    candidates: List[ImplicitCandidate],
    children: List[ImplicitTree],
    ambiguous: Boolean,
    cycle: Boolean
) derives ReadWriter

case class ImplicitChain(
    queryType: String,
    steps: List[ImplicitChainStep],
    resolved: Option[ImplicitTree] = None
) derives ReadWriter

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

// --- document outline -------------------------------------------------------

/** One declaration in a file's outline: its symbol, name, kind, the 0-based line of its definition,
  * and a `signature` rendered from SemanticDB — so types are the compiler-resolved ones and
  * implicit parameter lists are explicit (the "clarified" view), not the source's possibly-inferred
  * text. `children` nests members under their enclosing type/method.
  */
case class OutlineEntry(
    symbol: String,
    name: String,
    kind: SymbolKind,
    line: Int,
    signature: String,
    children: List[OutlineEntry]
) derives ReadWriter

// --- annotated source -------------------------------------------------------

/** One compiler insertion that is invisible in a file's source text, anchored at a 0-based `(line,
  * character)`. `kind` is one of `implicit` (a synthesised using-argument; its `character` is the
  * enclosing point, NOT a precise column), `implicit-conversion` (an inserted conversion, precise
  * `character`), `inferred-type-args` (type arguments the compiler inferred for a call, precise
  * `character`), or `inferred-type` (the result/value type of a definition the source left
  * unascribed). `text` is the rendered insertion, e.g. `(using sh)`, `toSeq(…)`, `[String]`, or `:
  * Future[Int]`.
  */
case class SourceAnnotation(line: Int, character: Int, kind: String, text: String)
    derives ReadWriter

// --- rename plan ------------------------------------------------------------

/** One edit in a rename: replace `oldText` in `range` of `uri` with `newText`. Ranges are the
  * compiler-resolved name occurrences, so there is no grep over-match (comments/strings/unrelated
  * same-named identifiers are never touched).
  */
case class RenameEdit(uri: String, range: Range, oldText: String, newText: String)
    derives ReadWriter

case class RenamePlan(
    symbol: String,
    fromName: String,
    toName: String,
    editCount: Int,
    edits: List[RenameEdit]
) derives ReadWriter

// --- move plan --------------------------------------------------------------

/** One file's import adjustment for a move: in `uri`, drop `removeImport` (if non-empty) and add
  * `addImport` (if non-empty), both as dotted fully-qualified names. A file already in the
  * destination package needs neither and is omitted from the plan.
  */
case class MoveImport(uri: String, removeImport: String, addImport: String) derives ReadWriter

/** The edits to move `symbol` from `fromOwner` to `toOwner` (both package symbols), keeping every
  * call/usage resolving. `definition` is the occurrence to relocate; `references` is every
  * compiler-resolved use across the project (so the move touches calls/usages, not just the body);
  * `imports` is the per-file import adjustment that move-induced FQN change requires. The server is
  * read-only — apply the returned edits yourself.
  */
case class MovePlan(
    symbol: String,
    displayName: String,
    kind: SymbolKind,
    fromOwner: String,
    toOwner: String,
    fromFqn: String,
    toFqn: String,
    definition: Option[Location],
    references: List[Location],
    imports: List[MoveImport]
) derives ReadWriter

// --- extract method plan ----------------------------------------------------

/** A name/type pair in an extracted method's surface (a parameter or a returned local). */
case class ExtractBinding(name: String, tpe: String) derives ReadWriter

/** The plan to extract the selected range of `uri` into a new method `methodName`. `parameters` are
  * the locals the selection reads but does not define (free variables → params); `returns` are the
  * locals it defines and that later code outside the selection still uses (→ the result).
  * `signature` is the new method's declaration and `call` is the expression that replaces the
  * selection (so both the extracted body AND its call site are covered). `enclosingMethod` is the
  * method the selection sits in, where the new method belongs. The server is read-only — apply the
  * edits yourself.
  */
case class ExtractMethodPlan(
    uri: String,
    range: Range,
    methodName: String,
    enclosingMethod: Option[SymbolRef],
    parameters: List[ExtractBinding],
    returns: List[ExtractBinding],
    returnType: String,
    signature: String,
    call: String
) derives ReadWriter

// --- call-graph path-find ---------------------------------------------------

case class CallEdge(from: SymbolRef, to: SymbolRef, at: Location) derives ReadWriter

case class CallGraphPath(
    from: SymbolRef,
    to: SymbolRef,
    path: List[SymbolRef],
    edges: List[CallEdge]
) derives ReadWriter

// --- call hierarchy --------------------------------------------------------

/** One node in the call hierarchy tree: the method itself, optional call-site location, and its own
  * callers/callees one level deeper.
  */
case class CallHierarchyNode(
    method: SymbolRef,
    at: Option[Location],
    children: List[CallHierarchyNode]
) derives ReadWriter

/** The call hierarchy for a method in one direction (callers or callees), as a tree rooted at the
  * queried symbol.
  */
case class CallHierarchy(
    symbol: String,
    displayName: String,
    direction: String,
    depth: Int,
    root: CallHierarchyNode
) derives ReadWriter

// --- value flow -------------------------------------------------------------

/** One node in a value-flow graph: a position where the traced value lives. `symbol` is the
  * SemanticDB symbol string for the binding/parameter/local the value occupies, `displayName` its
  * human name, `tpe` its rendered type, `location` its definition site (when known), `depth` how
  * many BFS hops it sits from the root, `enclosingMethod` the display name of the method that owns
  * this binding (None for top-level or unknown), and `kind` the SemanticDB symbol kind (LOCAL,
  * PARAMETER, FIELD, METHOD, …).
  */
case class ValueFlowNode(
    symbol: String,
    displayName: String,
    tpe: String,
    location: Option[Location],
    depth: Int,
    enclosingMethod: Option[String],
    kind: String
) derives ReadWriter

/** A directed flow edge: the value held by `from` reaches `to` via `relation` (`assigned_to`,
  * `passed_as_arg`, `method_receiver`, `returned_from`, `field_access`, `destructured`), observed
  * at the source position `at`. For `passed_as_arg`, `paramName` names the receiving parameter and
  * `coParameters` lists the other parameters at the same call site.
  */
case class ValueFlowEdge(
    from: String,
    to: String,
    relation: String,
    at: Location,
    paramName: Option[String],
    coParameters: List[String]
) derives ReadWriter

/** A leaf in the flow: `symbol` stops propagating with the given `classification`
  * (`function_result`, `method_receiver`, `type_widened`, `discarded`, `external_boundary`,
  * `depth_limit`), `at` its definition site when known. Used for both `stoppedAt` (a natural
  * terminal) and `truncatedAt` (cut off by the depth limit).
  */
case class ValueFlowTerminal(
    symbol: String,
    classification: String,
    at: Option[Location]
) derives ReadWriter

/** The result of tracing a value: the `root` binding, every `node` the value reaches, the `edges`
  * that carried it, the `stoppedAt` terminals (where it naturally stops), and the `truncatedAt`
  * nodes cut off by the `maxDepth` limit.
  */
case class ValueFlowResult(
    root: ValueFlowNode,
    nodes: List[ValueFlowNode],
    edges: List[ValueFlowEdge],
    stoppedAt: List[ValueFlowTerminal],
    truncatedAt: List[ValueFlowTerminal]
) derives ReadWriter

// --- smart-code-duplications -----------------------------------------------

case class DuplicateOccurrence(
    location: Location,
    enclosingMethod: Option[String]
) derives ReadWriter

case class DuplicationGroup(
    size: Int,
    astNodeCount: Int,
    occurrences: List[DuplicateOccurrence]
) derives ReadWriter

case class DuplicationsResult(
    groups: List[DuplicationGroup]
) derives ReadWriter
