# Property-Based Testing Audit — ScalaSemanticMCP

**Date:** July 2026  
**Scope:** 17 test suites across core, analysis, mcp, pc, sbt-plugin modules  
**Goal:** Classify tests for property-based (PB) vs. golden-value vs. example-based retention

---

## Summary Matrix

| Suite | Tests | PB | Golden | Example | Dogfood |
|---|---|---|---|---|---|
| SemanticIndexSuite | 5 | 0 | 0 | 5 | Yes |
| AnalyzerSuite | 18 | 1 | 4 | 13 | Yes |
| AnalyzerCoreSuite | 9 | 2 | 0 | 7 | No |
| AnalyzerToolsSuite | 11 | 2 | 0 | 9 | No |
| AnalyzerHelpersSuite | 18 | 3 | 0 | 15 | No |
| AnalyzerStructureSuite | 6 | 3 | 0 | 3 | Yes |
| AnalyzerValueFlowSuite | 2 | 0 | 0 | 2 | No |
| DuplicationAnalyzerSuite | 8 | 1 | 1 | 6 | No |
| AnalyzerPcSuite | 5 | 0 | 1 | 4 | No |
| CompatSuite | 9 | 0 | 0 | 9 | Yes |
| ModelsSuite | 4 | 1 | 1 | 2 | No |
| InputTypesSuite | 17+7 | 7* | 0 | 17 | No |
| StructureMetricsSuite | 17 | 4 | 0 | 13 | No |
| McpSuite | 22 | 0 | 3 | 19 | Yes |
| TokenMetricsSuite | 1 | 0 | 1 | 0 | Yes |
| PresentationCompilerBackendSuite | 5 | 0 | 0 | 5 | No |
| ConfigMergeSuite | 14 | 4 | 0 | 10 | No |
| **Totals** | **~161** | **~28** | **~11** | **~120** | — |

*InputTypesSuite has 7 existing `property(...)` blocks already converted; marked to show baseline.

---

## Suite-by-Suite Detail

### 1. SemanticIndexSuite (core) — Dogfood: YES
All 5 tests read live SemanticDB facts (`displayName`, `owner`, `ownerChain`, kind predicates).  
**Classification:** 5× example (tied to this repo's compiled symbols).

---

### 2. AnalyzerSuite (analysis) — Dogfood: YES
18 tests covering `findUsages`, `classHierarchy`, `members`, `resolveImplicits`, `callPath`, `movePlan`, `methodSignature`, `findOverloads`.

| Test Name | Classification | Reason |
|---|---|---|
| `findUsages with no pathFilter is unchanged` | PB | Invariant: `filter(None) == filter(default)` generalizes across any symbol |
| `methodSignature ... render` | Golden | Exact rendered signature string |
| `classHierarchy/members with no pathFilter` | PB | Invariant: `tool(sym, pathFilter=None) == tool(sym)` over dogfood symbols |
| `findOverloads` | Golden | Exact count and grouping assertion |
| `movePlan emits import swap` | Example | Specific regression scenario |
| Other tests (8) | Example | Scenario-shaped (specific symbol hierarchies, specific error messages) |

---

### 3. AnalyzerCoreSuite (analysis) — Dogfood: NO
9 tests on hand-built 3-class fixtures for hierarchy, members, signatures, overloads, call-graph shapes.

| Test Name | Classification | Reason |
|---|---|---|
| `methodSignature flags a list implicit iff non-empty AND all-implicit` | PB | Boolean invariant: `isImplicit == nonEmpty && allImplicit` over generated param lists |
| `resolveImplicits: chosen iff unique` | PB | Invariant: `chosen.isDefined == candidates.size == 1` |
| `classHierarchy/members/callHierarchy/findOverloads` (6 tests) | Example | Fixed 3-class hierarchy; each tests a distinct graph-shape edge case |

---

### 4. AnalyzerToolsSuite (analysis) — Dogfood: NO
11 tests covering outline, findSymbol ranking, movePlan, extractMethodPlan, structure, typeAtPosition, traceImplicitChain.

| Test Name | Classification | Reason |
|---|---|---|
| `findSymbol: exact>prefix>substring ranking` (2 tests) | PB | Ranking invariant: exact matches sort before prefix before substring, generalizes over symbol names |
| `rankedStructureSymbols sorts by metric` | PB | Sorting invariant: "non-increasing metric values after sort" |
| `outline nests members`, `movePlan`, `extractMethodPlan`, `typeAtPosition`, `traceImplicitChain` (8 tests) | Example | Scenario-shaped (free-var/escaping-local edge cases, specific formatted output) |

---

### 5. AnalyzerHelpersSuite (analysis) — Dogfood: NO
18 tests on utilities: `renderType`, `renderConstant`, `renderMethod`, range predicates, string scanners, hierarchy helpers, linearization.

| Test Name | Classification | Reason |
|---|---|---|
| `rangeContains is inclusive at start, exclusive at end` | PB | Boundary property over generated ranges/points |
| `rangeSpan weights lines over characters` | PB | Monotonicity: lines weighted higher than chars |
| `linearize de-duplicates a diamond` | PB | Invariant: "no duplicates in output" over random DAGs of class hierarchies |
| `renderType`, `renderConstant`, `renderMethod`, `alreadyAscribed`, `globMatcher`, `isImplicit`, `linearize/knownSubtypes`, etc. (15 tests) | Example | String scanners, fixed ADT enumerations, discrete cases (not infinite-domain) |

---

### 6. AnalyzerStructureSuite (analysis) — Dogfood: YES
6 tests on module graph, layering, centrality, instability.

| Test Name | Classification | Reason |
|---|---|---|
| `module instability reflects dependency direction` | PB | Invariant: instability ∈ [0,1], stable modules rank lower |
| `layering puts foundations below dependents` | PB | Invariant: `layer(child) > layer(parent)` across dependency edges |
| `per-symbol metrics carry all four dimensions; instability is a proper ratio` | PB | Invariant: instability formula and dimension wiring |
| `structure covers modules/types`, `module graph has no cycle`, `centrality favours foundations` (3 tests) | Example | Dogfood facts about this repo's real layering |

---

### 7. AnalyzerValueFlowSuite (analysis) — Dogfood: NO
2 tests on value-flow tracing: `traces value across assignment`, `depth limit truncates expansion`.

**Classification:** 2× example (fixed flow `a -> b -> sink(n)`; too scenario-specific to generalize).

---

### 8. DuplicationAnalyzerSuite (analysis) — Dogfood: NO
8 tests on code duplication detection: smart duplication, subsumption, independent blocks, normalization, filtering.

| Test Name | Classification | Reason |
|---|---|---|
| `normalize: locals → varN, literals → Lit...` | Golden | Normalized AST-printer string; snapshot-shaped |
| `analyze: minSize is an inclusive floor, not always-true` | PB | Boundary property: `groups.nonEmpty iff minSize <= maxBlockSize` over generated minSize |
| `detects smart/subsumed/independent` (5 tests) | Example | Each pins a distinct duplication scenario |

---

### 9. AnalyzerPcSuite (analysis) — Dogfood: NO (live PresentationCompilerBackend)
5 tests on buffer overlay, method-signature resolution, type-at-position, extractMethodPlan, close/bracket lifecycle.

| Test Name | Classification | Reason |
|---|---|---|
| `extractMethodPlan derives...` | Golden | Exact rendered signature/call string, but tied to PC compile |
| All 5 tests | Example | Inherently scenario/lifecycle-shaped (success path, failure path, buffer management); PC compilation is non-deterministic |

---

### 10. CompatSuite (analysis) — Dogfood: YES (cross-version golden fixtures)
9 tests per version (multi-version cross-factored).

**Classification:** 9× example (deliberately golden-fixture shaped, cross-version snapshots; inherently not PB-convertible). Already serving a snapshot role effectively.

---

### 11. ModelsSuite (model) — Dogfood: NO
4 tests on upickle round-trips: locations, symbol refs, method signature with implicit params, hierarchy/members/implicits/call-graph.

| Test Name | Classification | Reason |
|---|---|---|
| `locations/symbol refs/method signature/hierarchy round-trip` | Golden / PB | Hand-built instances round-tripped through upickle; already superseded by ModelsPropertySuite (see below) |
| `result JSON is field-named, not positional` | Example | Specific JSON key presence assertion |

---

### 12. InputTypesSuite (model) — Dogfood: NO (hybrid suite)
17 example tests + **7 existing `property(...)` blocks** (already converted).

| Existing Properties | Coverage |
|---|---|
| `SourcePosition.before/atOrBefore/atOrAfter` | Comparison operators |
| `PackageSymbol` idempotence | Round-trip (idempotent) |
| `NonNegativeInt`/`PositiveInt` boundary | Numeric range validation |
| `SourceRange.from`, `SourcePosition.from` | Constructor round-trips |

**Remaining 17 example tests** on `SemanticDbSymbol.from`, `MethodSymbol.from`, `TypeSymbol.from`, `DocumentUri.from`, `ScalaIdentifier.from`, etc.  
**Classification:** Example (error-message substrings best pinned literally, not generated; each tests specific accept/reject validation logic).

**Note:** This suite is the **gold standard template** for good PB conversion in this codebase; replicate its pattern elsewhere.

---

### 13. StructureMetricsSuite (analysis/graph) — Dogfood: NO
17 tests on coupling, instability, strongly-connected components, layers, PageRank, dependency graphs, module rollup.

| Test Name | Classification | Reason |
|---|---|---|
| `coupling counts in-project fan-in/fan-out` | PB | Pure arithmetic over generated graphs |
| `instability is Ce/(Ca+Ce)` | PB | Formula invariant over generated graphs |
| `stronglyConnectedComponents groups a cycle...` (2 tests) | PB | Invariant: "cyclic edges stay within one component; every node in exactly one" |
| `layers: longest dependency chain depth...` (2 tests) | PB | Invariant: "layer(child) > layer(parent) across non-cyclic edges" |
| `pageRank flows toward depended-on foundations` | PB | Comparative property: "more-depended-on > less-depended-on rank" (moderate confidence) |
| `DependencyGraphs.nodes/dimensions`, `per-type coupling`, `cycles/module rollup`, `callGraph/implicitGraph edge-filter` (8 tests) | Example | SemanticDB-shape-specific, hand-built documents (extends/memberType/call/implicit dimensions) |

**Note:** `GraphMetricsPropertySuite` already provides generators (`genNodes`, `genGraph`, `genNodeGraph`); pure functions in `GraphMetrics` could reuse them directly.

---

### 14. McpSuite (mcp) — Dogfood: YES
22 tests on JSON-RPC protocol: tools/list, request stream processing, invalid inputs, tool routing, schema validation.

| Test Name | Classification | Reason |
|---|---|---|
| `tools/list exposes all sixteen tools` | Golden | Exact tool count (19) and exact JSON shape |
| `process maps request stream to responses` | Golden | Exact JSON and response-stream structure |
| `invalid tool inputs are rejected` | Example | Distinct validation message per case |
| All other tests (19) | Example | Protocol round-trip and error-message scenarios |

---

### 15. TokenMetricsSuite (mcp) — Dogfood: YES (explicitly golden)
1 test: `token metrics artifacts match regenerated MCP-vs-baseline comparison`.

**Classification:** 1× golden (by design; diffs generated JSON/Markdown against checked-in golden files `docs/research/token-metrics.{json,md}`, with regen escape hatch `UPDATE_TOKEN_METRICS=1`).

**Note:** This is the **model golden-file suite** — already doing golden-file testing correctly; no conversion needed.

---

### 16. PresentationCompilerBackendSuite (pc) — Dogfood: NO (live PC compiler)
5 tests on buffer overlay, method-signature resolution, type-at-position, extractMethodPlan, useCurrentJvm/bracket lifecycle.

**Classification:** 5× example (inherently scenario/lifecycle-shaped; PC compilation is too expensive/non-deterministic for property generation).

---

### 17. ConfigMergeSuite (sbt-plugin) — Dogfood: NO
14 tests on JSON/TOML/YAML config merging: fresh file, add-alongside, replace-own, preserve-unrelated, **4 idempotency tests**, agent-specific steering.

| Test Name | Classification | Reason |
|---|---|---|
| `json/toml/yaml: idempotent` (3 + regression) | PB | Fixpoint property: `merge(merge(x)) == merge(x)` over random valid existing-config shapes; harness `assertIdempotent` already generic |
| `fresh file`, `adds/replaces/preserves content`, `writeRulesAndSteer` (10 tests) | Example | Scenario-shaped or file-system side-effect tests |

---

## Acknowledged Existing PB/Golden Suites

**Not deeply audited; noted as already-converted or representing best practice:**

- **AnalyzerGoldenSuite** — 1 dogfooded golden-snapshot test; output stability for representative symbol.
- **AnalyzerHelpersPropertySuite** — ScalaCheck properties over `genValidRange`/`genQueryPos`; covers `AnalyzerHelpers` boundary logic generatively.
- **ModelsPropertySuite** — ~25 generators (`genPosition` through `genDuplicationsResult`); `roundTrips[A](gen)` covers entire result-model catalog. **Supersedes ModelsSuite's manual round-trip tests.**
- **GraphMetricsPropertySuite** — `genNodes`/`genGraph`/`genNodeGraph` infrastructure for random digraphs; covers StructureMetricsSuite's pure functions.

---

## Top 5–8 Conversion Candidates (Ranked)

### 1. ConfigMergeSuite — idempotency tests (json/toml/yaml)
**Files/Lines:** `sbt-plugin/src/test/scala/…/ConfigMergeSuite.scala` (lines 83, 130, 182, 171)  
**Reason:** Harness `assertIdempotent` already exists and is generic; currently exercised on 2 hand-picked seed documents per format. Property: generate random valid existing-config documents (with/without server entry, with/without siblings) and assert `merge` is a fixpoint after one application. **Highest confidence, lowest effort in the codebase.**

### 2. ModelsSuite — manual round-trip tests
**Files/Lines:** `analysis/src/test/scala/…/ModelsSuite.scala` (lines 17–49)  
**Reason:** Directly superseded by already-existing `ModelsPropertySuite` generators. These 3 tests construct one hand-built instance per model and round-trip through upickle, exactly what `roundTrips[A](gen)` does generatively. **Recommend deleting/merging into ModelsPropertySuite rather than "converting."**

### 3. AnalyzerToolsSuite — findSymbol ranking tests
**Files/Lines:** `analysis/src/test/scala/…/AnalyzerToolsSuite.scala` (lines 91, 237)  
**Reason:** Property: "for any generated set of display names containing query as exact/prefix/substring, results sort exact > prefix > substring, ties broken by length." Currently 2 hardcoded symbol sets; ranking logic is a clean total-order property.

### 4. AnalyzerHelpersSuite — linearize de-duplicates a diamond
**Files/Lines:** `analysis/src/test/scala/…/AnalyzerHelpersSuite.scala` (line 222)  
**Reason:** Property: "for any randomly generated class-hierarchy DAG, `linearize` never returns duplicate ancestors." Currently one fixed 4-class diamond; generalizes naturally to arbitrary shapes and pairs with existing `AnalyzerHelpersPropertySuite` infrastructure.

### 5. StructureMetricsSuite — GraphMetrics pure functions
**Files/Lines:** `analysis/src/test/scala/…/StructureMetricsSuite.scala` (lines 16–53, 211–226)  
**Reason:** Pure functions over `Map[String, Set[String]]` graphs with no SemanticDB dependency. Properties like "instability == Ce/(Ca+Ce)", "every node in exactly one SCC", "layer(child) > layer(parent)" are textbook PB targets. `GraphMetricsPropertySuite` generators already exist; just need the assertions wired up.

### 6. AnalyzerCoreSuite — implicit/unique-resolution invariants
**Files/Lines:** `analysis/src/test/scala/…/AnalyzerCoreSuite.scala` (lines 106, 160)  
**Reason:** Two crisp boolean invariants: "methodSignature.isImplicit == nonEmpty && allImplicit" and "resolveImplicits.chosen.isDefined == candidates.size==1". Currently checked against 3 and 2 hand-built cases; easy to generalize over generated param-list/candidate-set sizes.

### 7. DuplicationAnalyzerSuite — minSize boundary
**Files/Lines:** `analysis/src/test/scala/…/DuplicationAnalyzerSuite.scala` (line 155)  
**Reason:** Property: "for the fixed duplicate block of size N, `analyze(minSize=m).groups.nonEmpty == (m <= N)`". Clean boundary property over generated `minSize` values against existing `dupIndex` fixture; replaces current 2-point spot-check.

### 8. AnalyzerSuite — dogfood-bound filter invariants
**Files/Lines:** `analysis/src/test/scala/…/AnalyzerSuite.scala` (lines 101, 134, 154)  
**Reason:** Dogfood-dependent but symbol-generatable: "for any symbol in dogfood index, `tool(sym, pathFilter=None) == tool(sym)`." Replace hardcoded 3 symbols with generative draw from `idx.symbols.keys`, broadening coverage cheaply while staying on real semantic data.

---

## Summary Statistics

- **Total test count:** ~161 tests across 17 suites
- **Current PB tests:** ~28 (InputTypesSuite's 7 existing blocks + newly identified candidates)
- **Golden-value candidates:** ~11 (snapshot/exact-output tests)
- **Example-based (keep as-is):** ~120 (scenario-specific, SemanticDB-dogfood dependent, or discretely-branched logic)

**Highest-ROI focus:** ConfigMergeSuite idempotency, ModelsSuite (consolidate into ModelsPropertySuite), AnalyzerToolsSuite ranking, AnalyzerHelpersSuite linearization.

**Next step:** Select top 3–4 candidates for pilot conversion, measure test execution time and assertion clarity improvement, then scale pattern to lower-priority candidates.
