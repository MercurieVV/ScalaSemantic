# Opaque Types — Candidate Survey (Issue #69)

## What is already in place

`InputTypes.scala` (`analysis` module) validates raw JSON strings at the MCP boundary before analysis logic consumes them:

| Type | Underlying | Validation |
|---|---|---|
| `SemanticDbSymbol` | `String Refined NonEmpty` | global or local SemanticDB symbol grammar |
| `MethodSymbol` | `SemanticDbSymbol` | must be a global method descriptor |
| `TypeSymbol` | `SemanticDbSymbol` | must be a global type descriptor |
| `PackageSymbol` | `String` | slash-separated segments, no `#`/`.`/`()`/`[]` |
| `DocumentUri` | `String Refined NonEmpty` | relative, no leading `/`, no `..` |
| `ScalaIdentifier` | `String Refined NonEmpty` | plain / symbolic / backticked, not a keyword |
| `NonNegativeInt` | `Int Refined NonNegative` | `>= 0` |
| `PositiveInt` | `Int Refined Positive` | `> 0` |
| `SourcePosition` | case class | wraps two `NonNegativeInt` |
| `SourceRange` | case class | wraps two `SourcePosition`; end strictly after start |
| `StructureDimension` | enum | `combined / extends / memberType / call / implicit` |
| `StructureSort` | enum | `afferent / efferent / instability / layer / centrality / sccSize` |
| `SourceFormat` | enum | `annotated / compilable / plain` |

All have `from` smart constructors returning `Either[String, T]`, exercised by `InputTypesSuite` (including ScalaCheck property tests).

## Candidates examined — and why each is not actionable

| Candidate | Reason not to wrap |
|---|---|
| Internal symbol strings in `SemanticIndex` / `AnalyzerHelpers` / `DependencyGraphs` (`Map[String, ...]`, `occurrencesOf`, etc.) | Originate from the protobuf API — already valid by construction. Large refactor surface, zero safety gain. |
| Wire models in `Models.scala` (`Position`, `Location`, `SymbolRef`, …) | Derive `upickle.ReadWriter` directly. Opaque types require custom serializers; extra indirection with no safety benefit (values are analysis output, not user input). |
| `pathFilter: Option[String]` (glob pattern) | Every string is a valid glob — nothing to validate in a smart constructor. |
| `Graph = Map[String, Set[String]]` in `GraphMetrics` | Internal type alias; constructed internally, not from user input. Making it opaque requires exposing `Map` operations with no benefit. |
| Module-name string in `DependencyGraphs.moduleOf` | Purely internal graph node; never user input. |

## Recommendation

The current design is sound. `InputTypes` provides a clean, tested validation gateway between raw MCP JSON and the domain engine. No further opaque types are clearly safe and high-value to add without crossing the JSON serialization boundary or requiring a large internal refactor of the index layer.

If internal symbol strings ever need distinguishing from arbitrary strings (e.g. preventing a URI where a symbol is expected), introduce a `core`-layer opaque type in a dedicated PR with its own test pass — the refactor surface is large enough to warrant isolation.
