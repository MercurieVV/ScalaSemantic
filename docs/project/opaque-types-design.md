# Opaque Types — Candidate Survey (Issue #69)

## What is already in place

`InputTypes.scala` (`analysis` module) already applies opaque types and smart constructors at the
right boundary — the point where raw JSON strings enter the domain and must be validated before
analysis logic consumes them:

| Type | Underlying | Validation |
|---|---|---|
| `SemanticDbSymbol` | `String Refined NonEmpty` | global or local SemanticDB symbol grammar |
| `MethodSymbol` | `SemanticDbSymbol` | must be a global method descriptor |
| `TypeSymbol` | `SemanticDbSymbol` | must be a global type descriptor |
| `PackageSymbol` | `String` | slash-separated segments, no `#`/`.`/`()`/`[]` chars |
| `DocumentUri` | `String Refined NonEmpty` | relative, no leading `/`, no `..` traversal |
| `ScalaIdentifier` | `String Refined NonEmpty` | plain / symbolic / backticked, not a keyword |
| `NonNegativeInt` | `Int Refined NonNegative` | `>= 0` |
| `PositiveInt` | `Int Refined Positive` | `> 0` |
| `SourcePosition` | case class | wraps two `NonNegativeInt` |
| `SourceRange` | case class | wraps two `SourcePosition`; end strictly after start |
| `StructureDimension` | enum | `combined / extends / memberType / call / implicit` |
| `StructureSort` | enum | `afferent / efferent / instability / layer / centrality / sccSize` |
| `SourceFormat` | enum | `annotated / compilable / plain` |

All of these have `from` smart constructors returning `Either[String, T]` and are exercised by
`InputTypesSuite` (including ScalaCheck property tests for the ordering predicates and package
normalization).

The public `Analyzer` API consumes these types directly:
`findUsages(symbol: SemanticDbSymbol)`, `classHierarchy(symbol: TypeSymbol)`,
`renamePlan(symbol: SemanticDbSymbol, newName: ScalaIdentifier)`, etc.

## Candidates examined — and why each is not immediately actionable

### 1. Internal symbol strings in `SemanticIndex` / `AnalyzerHelpers` / `DependencyGraphs`

**What:** The `Map[String, s.SymbolInformation]`, `Map[String, Vector[...]]`, and all the raw
`String` parameters in `occurrencesOf`, `document`, `info`, `displayName`, `owner`, etc.

**Why not:** These strings originate from the ScalaMeta / protobuf API
(`s.SymbolOccurrence.symbol`, `s.TextDocument.uri`, …) and cannot be meaningfully validated at
ingestion — if the protobuf emitted it, it is by definition a valid symbol or URI for that index.
Wrapping them into an opaque type would require:
- A bypass constructor (defeating the purpose), or
- Touching every `Map` and every method signature across `core`, `analysis`, `pc`, and `mcp`.

The refactor surface is large (every internal `String` in the index layer) and the safety gain is
zero (nothing is validated that isn't already guaranteed by the emitter). The validated gateway
already sits one layer up in `InputTypes`.

### 2. Wire models in `Models.scala`

**What:** `case class Position(line: Int, character: Int)`, `Location(uri: String, …)`,
`SymbolRef(symbol: String, …)`, etc.

**Why not:** These derive `upickle.ReadWriter` and are serialized directly to JSON as MCP tool
results. Opaque types require custom `ReadWriter` instances to serialize transparently, and the
extra indirection adds complexity with no type-safety benefit (the values are already valid at this
point — they were produced by the analysis engine, not taken from user input).

### 3. `pathFilter: Option[String]` (glob pattern)

**What:** Appears on `findUsages`, `classHierarchy`, `members`, `findSymbol`,
`rankedStructureSymbols`, `analyzeDuplications`, and `AnalyzerHelpers.globMatcher /
bySymbolPath`.

**Why not:** A glob pattern has no structural invariant to enforce — every `String` is a valid
glob (`*` matches any run of characters, the rest is literal). There is nothing to validate in a
smart constructor, so the opaque type would be a newtype alias with no benefit over the current
`Option[String]`.

### 4. `Graph = Map[String, Set[String]]` in `GraphMetrics`

**What:** The internal directed-graph representation used by `DependencyGraphs` and
`StructureMetrics`.

**Why not:** Already a named type alias inside `GraphMetrics`. Making it opaque would require
exposing all `Map` operations via extension methods (or delegating via `value`) for no validation
benefit — the graph is constructed internally, not received from external input.

### 5. Module-name string in `DependencyGraphs.moduleOf`

**What:** The leading path segment of a type's definition URI, e.g. `"core"`, `"analysis"`.
Used as graph nodes in `moduleGraph` and `ModuleStructure`.

**Why not:** Purely internal to the graph layer; never accepted as user input or JSON input. An
opaque type would add boilerplate (boxing/unboxing inside a single private method) with no
external visibility.

## Recommendation

The current design is sound: the `InputTypes` layer provides a clean, tested validation gateway
between raw MCP JSON strings and the domain engine. No further opaque types are clearly safe and
high-value to add without either:
- crossing the JSON serialization boundary (wire models), or
- requiring a large internal refactor of the SemanticDB/protobuf layer (index internals).

If the internal symbol strings ever need to be distinguished from arbitrary strings (e.g. to
prevent passing a URI where a symbol is expected inside the index layer), the right approach is
to introduce a separate `core`-layer opaque type for SemanticDB symbol strings and update
`SemanticIndex` plus its callers in a dedicated refactor tracked as a separate issue — the risk
profile is high enough that it warrants its own PR and test pass.
