# Readability cleanup — Phase 1 catalog

Scope: `core/`, `analysis/`, `mcp/` main sources (tests excluded). Produced by scanning the
SemanticDB-backed structural view of the repo (`document_outline`, `annotated_source`) rather than
grepping the `.scala` text, per project convention.

This is a **catalog only** — no code changes. Each entry: location, the pattern, a before/after
sketch, and an effort estimate (trivial / small / medium). Phase 2+ would implement a subset of
these, prioritized separately.

---

## 1. `Analyzer.valueFlow` — long single-method orchestration (227 lines)

**File:** `analysis/src/main/scala/.../analysis/Analyzer.scala:730-957` (`valueFlow`)

The method defines 11 nested local functions (`isValueRef`, `headType`, `widened`,
`definitionLocation`, `node`, `valueParamSymbols`, `startCol`/`endCol`/`pos`, `isTailReturn`,
`classifyRef`, `classify`, `terminalClass`, `loop`) inside one `def`, several of which nest 3-4
levels of `match`/`filter`/`fold` themselves (`classifyRef` in particular: a `match` containing an
`if`, containing a `match`, containing another `match`). It is the single largest concentration of
nested logic in the codebase.

**Before:** all 11 helpers as locals inside `valueFlow`, sharing closure state (`limit`, `index`, `h`).
**After:** extract the position/ordering helpers (`startCol`, `endCol`, `pos`, `isTailReturn`,
`maxByStartCol`) and the pure classification (`classifyRef`, `classify`, `terminalClass`,
`widened`, `headType`) into a private `ValueFlowClassifier` (or onto `AnalyzerHelpers`), leaving
`valueFlow` itself as the BFS driver (`loop` + `node` + the final `ValueFlowResult` assembly).
Mirrors the existing `Analyzer` / `AnalyzerHelpers` split already used for the rest of the class.

**Effort:** medium (the locals close over `index`/`h`/`limit`/`stopOnTypeWidening`; extraction needs
explicit parameters, and the method has no test seams of its own yet — covered only via
`AnalyzerValueFlowSuite` end-to-end).

---

## 2. `Analyzer.valueFlow.classifyRef` — 4-level nested match/if

**File:** `analysis/src/main/scala/.../analysis/Analyzer.scala` (inside `valueFlow`, `classifyRef`)

```scala
// before (sketch)
occ.range.map { r =>
  ...
  if receiver then Flow("method_receiver", None, at)
  else
    val before = onLine.filter(o => startCol(o) < col)
    ...
    maxMethodBefore match
      case None =>
        maxLocalDef match
          case Some(ld) => Flow("assigned_to", Some(ld.symbol), at)
          case None =>
            if isTailReturn(doc, occ) then Flow("returned_from", None, at)
            else Flow("discarded", None, at)
      case Some(callee) =>
        ...
}
```

**After:** flatten the `if/match/match/if` chain into a single `match` over a small ADT/tuple
(`(receiver, maxMethodBefore, maxLocalDef, isTail)`), or split into `classifyReceiver`,
`classifyAssignment`, `classifyTerminal` returning `Option[Flow]`, chained with `orElse`. Reduces
peak nesting from 4 to 1-2.

**Effort:** small-medium (logic is intricate and load-bearing; needs care + the existing
`AnalyzerValueFlowSuite` regression coverage before/after).

---

## 3. Repeated "tagged constructor wrapper" boilerplate in `InputTypes.scala`

**File:** `analysis/src/main/scala/.../model/InputTypes.scala`

Ten opaque-type-style wrappers (`SemanticDbSymbol`, `MethodSymbol`, `TypeSymbol`, `PackageSymbol`,
`DocumentUri`, `NonNegativeInt`, `PositiveInt`, `SourcePosition`, `SourceRange`, `ScalaIdentifier`,
`StructureDimension`, `StructureSort`, `SourceFormat`) each repeat the same `from`/`value`
companion-object shape (smart constructor returning `Either[String, T]` + a plain accessor). This
is intentional validation-at-the-boundary design, not an accident, but the ceremony is duplicated
13 times with no shared abstraction.

**Before:** 13 independent `object Foo: def from(...): Either[String, Foo] = ...; def value(f: Foo): T = ...`.
**After:** a small generic helper, e.g. a `Refined[T]` trait/mixin contributing `from`/`value` given
a validation predicate, or at minimum a shared `private def validated[T](cond: Boolean, msg: => String)(mk: => T): Either[String, T]`
to collapse the repeated `if cond then Right(...) else Left(...)` bodies.

**Effort:** small (mechanical, but touches every call site indirectly via the companion API — keep
the public `from`/`value` signatures stable to avoid a wider blast radius).

---

## 4. `McpTools.scala` — 28 near-identical `argX` extractors (verbose, repetitive)

**File:** `mcp/src/main/scala/.../mcp/McpTools.scala:919-977`

```scala
def argStr(a: Value, k: String): String = ...
def argInt(a: Value, k: String, d: Int): Int = ...
def argBool(a: Value, k: String, d: Boolean): Boolean = ...
def argSymbol(a: Value, k: String): SemanticDbSymbol = ...
def argMethodSymbol(a: Value, k: String): MethodSymbol = ...
def argTypeSymbol(a: Value, k: String): TypeSymbol = ...
def argPackageSymbol(a: Value, k: String): PackageSymbol = ...
def argUri(a: Value, k: String): DocumentUri = ...
def argIdentifier(a: Value, k: String): ScalaIdentifier = ...
def argIdentifier(a: Value, k: String, default: String): ScalaIdentifier = ...
def argNonNegativeInt(a: Value, k: String, default: Int): NonNegativeInt = ...
def argPositiveInt(a: Value, k: String, default: Int): PositiveInt = ...
... (argDimension, argSort, argFormat similarly)
```

Each of `argSymbol`/`argMethodSymbol`/`argTypeSymbol`/`argPackageSymbol`/`argUri`/`argIdentifier`/
`argNonNegativeInt`/`argPositiveInt` follows the identical shape: pull `argStr`/`argInt`, call the
type's `.from(...)`, and `.fold(error, identity)`.

**Before:** one bespoke method per refined type.
**After:** a single generic `def argRefined[T](a: Value, k: String)(from: String => Either[String, T]): T = from(argStr(a, k)).fold(error, identity)`,
with each `argX` reduced to a one-line `argRefined(a, k)(SemanticDbSymbol.from)`. Cuts ~25 lines to
~10 and makes adding a new refined arg type a one-liner.

**Effort:** trivial (pure refactor, identical call sites/signatures preserved, good test coverage
via `McpSuite`).

---

## 5. `McpTools.argPosition` / `argRange` — verbose inline construction

**File:** `mcp/src/main/scala/.../mcp/McpTools.scala:953-967` (signatures only known via outline;
representative of the `argX` family above doing multi-field assembly inline rather than delegating)

**Pattern:** position/range argument parsing duplicates the "extract 2-4 ints, build the value
type, fold the Either" sequence that the simpler `argX` helpers already do individually — a good
candidate to build on top of item 4 rather than duplicate it further.

**Effort:** trivial-small (depends on item 4 landing first).

---

## 6. Verbose `Option[Tuple2[String, Value]]*` plumbing — `jobj`/`opt`

**File:** `mcp/src/main/scala/.../mcp/McpTools.scala:911-918`

```scala
def jobj(fields: Option[(String, Value)]*): Value = ...
def opt(cond: Boolean, field: => (String, Value)): Option[(String, Value)] = ...
```

These two tiny helpers are used pervasively to build sparse JSON objects (drop empty fields rather
than emit `null`). The names are already short, but every call site reads as
`jobj("a" -> x, opt(cond, "b" -> y), ...)` mixing `(String, Value)` and `Option[(String, Value)]`
positionally — easy to typo. Not urgent, but a builder-style API
(`JsonBuilder().field("a", x).fieldIf(cond, "b", y).build()`) would read more linearly at each of
the ~15 call sites across `McpTools`.

**Effort:** small (low risk, but touches many call sites for a readability-only gain — lower
priority than items 3/4).

---

## 7. `Analyzer.movePlan` — nested `if/else if/else` import-decision chain

**File:** `analysis/src/main/scala/.../analysis/Analyzer.scala` (`movePlan`, the `imports` val)

```scala
.flatMap { uri =>
  val pkg = h.documentPackage(uri)
  if pkg.contains(toOwner) then None
  else if pkg.contains(fromOwner) then Some(MoveImport(uri, "", toFqn))
  else Some(MoveImport(uri, fromFqn, toFqn))
}
```

Readable as-is, but the three-way `if/else if/else` returning `None`/`Some`/`Some` reads more
clearly as a `match` over the documented three cases ("already in destination" / "was in source
package" / "elsewhere"), echoing the prose already in the doc comment immediately above it.

**Before:** chained `if/else if/else`.
**After:**
```scala
.flatMap { uri =>
  h.documentPackage(uri) match
    case Some(p) if p == toOwner   => None
    case Some(p) if p == fromOwner => Some(MoveImport(uri, "", toFqn))
    case _                          => Some(MoveImport(uri, fromFqn, toFqn))
}
```

**Effort:** trivial.

---

## 8. `GraphMetrics.layers` — deeply nested memoized recursion (`level`)

**File:** `analysis/src/main/scala/.../analysis/graph/GraphMetrics.scala:96-108`

```scala
def level(c: Int, memo: Map[Int, Int]): (Int, Map[Int, Int]) =
  memo.get(c) match
    case Some(v) => (v, memo)
    case None =>
      val (lv, m) =
        condensed.getOrElse(c, Set.empty).foldLeft((0, memo)) { case ((acc, mm), s) =>
          val (sv, mm2) = level(s, mm)
          (PureKernels.nextLevel(acc, sv), mm2)
        }
      (lv, m + (c -> lv))
```

A `match` containing a `val` containing a `foldLeft` containing a destructuring `case` that
recurses — 4 syntactic levels for a fairly small piece of logic. The threading of `(value, memo)`
tuple pairs through `foldLeft` is the main readability cost (generic memoization boilerplate, not
domain logic). A small private `Memo[K, V]` mutable-inside/pure-outside helper (or `mutable.Map`
scoped locally, common and accepted for memoization even in otherwise-immutable code) would let
`level` read as `memo.getOrElseUpdate(c, condensed.getOrElse(c, Set.empty).map(level).foldLeft(0)(PureKernels.nextLevel))`.

**Effort:** small (well-isolated private function with existing `StructureMetricsSuite`/
`GraphMetricsPropertySuite` coverage; behavior-preserving local change).

---

## 9. `Analyzer.findSymbol` — verbose multi-stage filter pipeline could read as guard clauses

**File:** `analysis/src/main/scala/.../analysis/Analyzer.scala` (`findSymbol`)

Six chained `.filter` calls plus a `.sortBy` with an inline 3-tuple rank computation:
```scala
index.symbols.values.iterator
  .filter(si => index.isGlobal(si.symbol))
  .filter(si => !findSymbolExcludedKinds.contains(si.kind))
  .filter(si => si.displayName.nonEmpty && si.displayName != "<init>")
  .filter { si => val n = si.displayName.toLowerCase; if exact then n == q else n.contains(q) }
  .filter(si => wantedKind.forall(k => si.kind.toString.toUpperCase == k))
  .filter(si => keepPath(si.symbol))
  .toList
  .sortBy { si => ... 3-line rank computation ... }
```
Functionally fine and idiomatic Scala, but six sequential single-purpose `.filter`s could be
collapsed into one combined predicate `def matches(si): Boolean` for a single `.filter(matches)`,
improving scan-ability without changing evaluation order materially (filters are all cheap/pure).

**Effort:** small (readability-only; no behavior change since filters are independent/commutative
here).

---

## 10. Verbose local name `localTypeText` / `localName` vs. terse usage (naming asymmetry)

**File:** `analysis/src/main/scala/.../analysis/AnalyzerHelpers.scala:168-181`

```scala
def localName(symbol: String): String = ...
def localTypeText(symbol: String): String = ...
```
vs. sibling helpers in the same file: `typeString`, `kindName`, `symbolRef` — three different naming
conventions for "render X about a symbol as text" (`localTypeText` vs `typeString`, `localName` vs
plain `displayName` already on `SemanticIndex`). Not wrong, but a newcomer has to learn 4 near-
synonyms (`displayName`, `localName`, `typeString`, `localTypeText`) for closely related "give me
text for this symbol" operations. Worth a short naming pass (e.g. `localName` → could just call
`index.displayName` directly per its own doc comment "locals carry no descriptor... fall back to
the index" — the indirection may not earn its name).

**Effort:** trivial (a naming/inlining pass, no logic change; verify via `rename_plan` to avoid
missing call sites).

---

## 11. `DuplicationAnalyzer.scala` — multiple tree-walking helpers with similar shape, no shared traversal

**File:** `analysis/src/main/scala/.../analysis/DuplicationAnalyzer.scala`

`nodeCount`, `findSubtrees`, `isDescendant`, `collectLocalNames`, `collectReferencedNames` all walk
a `scalameta` `Tree` recursively with slightly different accumulation logic (count vs. collect vs.
membership test). They don't share a common fold/traversal primitive, so each re-implements
"recurse into children" via `tree.children` slightly differently (worth checking whether scalameta
already exposes a `Tree.transform`/fold combinator that could replace 3-4 of these with one
`collect[T]` traversal parameterized by what to extract).

**Effort:** medium (requires checking scalameta's tree API for a suitable existing combinator;
touches core duplication-detection logic that has dedicated `DuplicationAnalyzerSuite` coverage).

---

## 12. `Mcp.resolveClasspath` — deep `Option` chain with an embedded multi-line comment explaining a subtle case

**File:** `mcp/src/main/scala/.../mcp/Mcp.scala:255-278`

```scala
arg
  .orElse(Option(System.getenv("SCALASEMANTIC_CLASSPATH")))
  .map(_.trim)
  .filter(_.nonEmpty)
  .map { spec =>
    // A spec with no path separator that names nothing on disk is a classpath FILE...
    val asFile = Paths.get(spec)
    val missingFileRef = !spec.contains(java.io.File.pathSeparator) && !Files.exists(asFile)
    if missingFileRef then Vector.empty
    else
      val raw = if Files.isRegularFile(asFile) then Files.readString(asFile) else spec
      raw.split(...).iterator.map(_.trim).filter(_.nonEmpty).map(Paths.get(_)).toVector
  }
  .filter(_.nonEmpty)
```

The inner `map { spec => ... }` body (file-vs-literal-classpath resolution) is a distinct concern
from the outer `Option` plumbing (arg vs env var vs absence). Extracting it as a named private
method `resolveSpec(spec: String): Vector[Path]` would let the outer chain read as a flat
`arg.orElse(envVar).map(_.trim).filter(_.nonEmpty).map(resolveSpec).filter(_.nonEmpty)` and isolate
the "missing file ref" edge case (already explained by a comment, but easier to find/test once
named).

**Effort:** trivial-small.

---

## 13. `Mcp.handle` — 3-level nested pattern match on JSON-RPC method dispatch

**File:** `mcp/src/main/scala/.../mcp/Mcp.scala:90-153`

```scala
method match
  case "initialize" => idOpt.map(id => ok(id, obj(...)))           // ~20 lines inline
  case "tools/list" => idOpt.map(id => ok(id, obj("tools" -> ...))) // ~8 lines inline
  case "tools/call" =>
    idOpt.map { id =>
      ...
      tools.find(_.name == name) match
        case None => err(...)
        case Some(tool) =>
          onToolCall(name, args)
          scala.util.Try(tool.run(args)) match
            case scala.util.Success(res) => ok(...)
            case scala.util.Failure(e)   => ok(..., "isError" -> true)
    }
  case "ping" => ...
  case m if m.startsWith("notifications/") => None
  case _ => idOpt.map(id => err(...))
```
The `"tools/call"` branch alone nests `idOpt.map { match { case Some(tool) => Try(...) match { ... } } }`
— 3 levels for one branch out of 6. Extracting `handleToolsCall(req, tools, onToolCall, id): Value`
(and similarly `handleInitialize`) as private methods would flatten the top-level `match` to one
line per case, matching the brevity already achieved by `"ping"`/`"notifications/"`/the default.

**Effort:** small (pure extraction, `McpSuite` already exercises every branch via JSON-RPC
fixtures).

---

## 14. `Analyzer.outline` — local-function nesting for parent/child tree assembly

**File:** `analysis/src/main/scala/.../analysis/Analyzer.scala` (`outline`)

```scala
index.document(uri.value).map { doc =>
  val defs = doc.occurrences.toList.collect { case occ if ... => occ.symbol -> ... }.distinctBy(_._1)
  val definedSet = defs.map(_._1).toSet
  def parentOf(sym: String): Option[String] = Some(index.owner(sym)).filter(definedSet.contains)
  def build(sym: String, line: Int): OutlineEntry =
    val kids = defs.filter((c, _) => parentOf(c).contains(sym)).sortBy(_._2)
    OutlineEntry(sym, index.displayName(sym), h.kindName(sym), line, outlineSignature(sym),
                 kids.map((c, l) => build(c, l)))
  defs.filter((sym, _) => parentOf(sym).isEmpty).sortBy(_._2).map((sym, l) => build(sym, l))
}
```
`build` recursively calls itself inside a `.map` inside the outer `.map` — readable but doing
double duty (computing children via a full linear scan of `defs` for every node, i.e. O(n²)).
Pulling `parentOf`/`build` out as a private nested object/class (or grouping children once via
`defs.groupBy(parentOf)` up front) would both flatten the nesting and fix the quadratic scan in one
pass — a rare case where the readability fix and a minor perf fix coincide.

**Effort:** small.

---

## 15. Overly generic tuple-typed signatures surfaced in `document_outline` (`Tuple2`/`Tuple3` in public-ish method signatures)

**Files:** widespread — e.g. `Analyzer#callGraph: Map[String, List[Tuple2[String, Location]]]`,
`Analyzer#rankedStructureSymbols(...): List[Tuple2[SymbolStructure, DimensionMetrics]]`,
`AnalyzerHelpers#memberInfo`'s callers building `Tuple3` sort keys inline (`(uri, line, character)` /
`(rank, name.length, symbol)` in `findSymbol`/`renamePlan`).

**Pattern:** several internal APIs return bare `(String, Location)` / `(SymbolStructure,
DimensionMetrics)` pairs rather than small named case classes (`CallEdgeEntry`,
`RankedSymbol`). Untyped tuples are fine for fully-local, short-lived intermediate values (and most
uses here are exactly that), but a few — like `callGraph`'s `Map[String, List[(String,
Location)]]` — are `private lazy val`s threaded through multiple methods (`callPath`,
`callHierarchy`) where a one-line case class (`CallEdgeEntry(callee: String, at: Location)`) would
self-document the tuple's field meaning at every use site instead of positional `._1`/`._2` (e.g.
`adjacency.getOrElse(node, Nil).map(_._1)` in `callPath`'s `bfs`).

**Effort:** small (1-2 case classes, confined to `Analyzer`'s private call-graph machinery; no
public API change since `callGraph`/`reverseCallGraph` are already `private`).

---

## Summary table

| # | Location | Pattern | Effort |
|---|----------|---------|--------|
| 1 | `Analyzer.valueFlow` | 11 nested local fns in one 227-line method | medium |
| 2 | `Analyzer.valueFlow.classifyRef` | 4-level nested match/if | small-medium |
| 3 | `InputTypes.scala` | 13× duplicated smart-constructor boilerplate | small |
| 4 | `McpTools.scala` argX family | 8+ duplicated `from(...).fold(error, identity)` extractors | trivial |
| 5 | `McpTools.argPosition`/`argRange` | inline multi-field assembly, builds on #4 | trivial-small |
| 6 | `McpTools.jobj`/`opt` | positional `(String,Value)` tuple plumbing at ~15 call sites | small |
| 7 | `Analyzer.movePlan` imports | 3-way `if/else if/else` → `match` | trivial |
| 8 | `GraphMetrics.layers.level` | 4-level nested memoized recursion | small |
| 9 | `Analyzer.findSymbol` | 6 chained `.filter`s → 1 combined predicate | small |
| 10 | `AnalyzerHelpers` naming | 4 near-synonym "symbol → text" helpers | trivial |
| 11 | `DuplicationAnalyzer.scala` | 5 tree walkers w/o shared traversal primitive | medium |
| 12 | `Mcp.resolveClasspath` | inner spec-resolution logic inlined in `Option` chain | trivial-small |
| 13 | `Mcp.handle` | 3-level nested match for `"tools/call"` branch | small |
| 14 | `Analyzer.outline` | recursive nested fn, incidental O(n²) | small |
| 15 | `Analyzer`/helpers tuple returns | bare `Tuple2`/`Tuple3` instead of named case classes | small |

## Notes for phase 2+

- Items 4, 7, 10, 12 are the cheapest, lowest-risk wins — good candidates for a first
  implementation pass.
- Items 1, 2, 11 touch the most behaviorally-sensitive code (`valueFlow`,
  `DuplicationAnalyzer`) and should be done with the existing test suites
  (`AnalyzerValueFlowSuite`, `DuplicationAnalyzerSuite`) run before/after, ideally with no
  assertion changes (pure refactor, verified via `sbt prePush`).
- No code was changed in this phase; `sbt prePush` was not required to pass against new code,
  only the catalog document was added.
