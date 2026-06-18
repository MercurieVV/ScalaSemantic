# ScalaSemanticMCP — Plan & Execution Tracker

Living doc. Update status as work lands. Status: ⬜ todo · 🔄 in-progress · ✅ done · ⛔ blocked

## Goal
MCP server doing deep semantic analysis on Scala via SemanticDB — beyond Metals/LSP.

## Architecture
`MCP stdio JSON-RPC` → `analysis engine` → `SemanticIndex` (loader, done).
- No Scala MCP SDK → hand-rolled JSON-RPC over stdin/stdout (upickle).
- Signature rendering: custom `Type`/`Signature` printer; implicits via `SymbolInformation.Property.IMPLICIT`.
- Call graph: edges from `SymbolOccurrence` refs inside a method's def range; BFS for paths.

## Phases

| # | Phase | Status | Notes |
|---|-------|--------|-------|
| 0 | Setup: CLAUDE.md, prePush, configs, commit | ✅ | `sbt prePush` green |
| 1 | SemanticDB study + symbol-grammar helpers (fix `owner` dead code) | ✅ | delegates to `Scala.*`; 5 tests |
| 2 | Result models (upickle case classes) | ✅ | one per tool; round-trip tests |
| 3 | Analysis engine — query methods | ✅ | 9/9 done, 23 tests |
| 4 | MCP protocol layer (JSON-RPC, tool registry) | ✅ | stdio, 9 tools, lean-by-default |
| 5 | Tests: dogfood + Metals comparison | 🔄 | 33 tests green; comparison writeup pending |

## Phase 3 — query methods (one MCP tool each)
- ✅ find-usages (cross-file references)
- ✅ method-signature (incl. implicit/using params + type renderer)
- ✅ class-hierarchy / trait relationships (+ known-subtypes scan)
- ✅ resolve-implicits for a type (given defs producing it; filters params/synthetics)
- ✅ type-at-position (most-specific occurrence at a position)
- ✅ find-overloads (group by owner + name)
- ✅ trait-vs-local members (declared vs inherited, override-aware)
- ✅ trace-implicit-chain (given deps via implicit param types)
- ✅ call-graph + path-find (source-order enclosing-method attribution + BFS)

## Setup checklist (Phase 0)
- ✅ CLAUDE.md
- ✅ project/plugins.sbt (scalafix, scalafmt, wartremover)
- ✅ .scalafmt.conf, .scalafix.conf
- ✅ build.sbt: prePush task, wart warnings, semanticdb
- ✅ resolve meta-build cross-version conflict (`conflictWarning := disable` in plugins.sbt)
- ✅ verify `sbt prePush` runs green
- ✅ initial git commit (36aa0ee)

## MCP interface (Phase 4)
- Transport: newline-delimited JSON-RPC 2.0 over stdio (`Mcp.serve`); pure `Mcp.handle`/`Mcp.process` for testing. Run: `runMain scalasemantic.mcpServer <root>`.
- Token discipline (per request): lean by default — locations as `uri:line:col`, signatures as one rendered line, related symbols as display names; empty fields omitted. `"detailed": true` opts into structured breakdowns; `find_usages` is paged (`limit`/`offset` + `referenceCount`).
- 9 tools: find_usages, method_signature, class_hierarchy, find_overloads, members, type_at_position, resolve_implicits, trace_implicit_chain, call_path.

## Known issues / decisions
- **sbt 2.0.0 API shifts:** `test` is now an `InputKey` → use `(Test / test).toTask("")`; task result caching needs a `HashWriter` → wrap aggregate task in `Def.uncached(...)`.
- **Meta-build conflict:** sbt 2.0 meta-build is Scala 3; plugins drag `_2.13` scala-collection-compat → `ConflictWarning.disable` in `project/plugins.sbt`.
- **SemanticDB bindings:** `scala.meta.internal.semanticdb.*` lives in artifact `semanticdb-shared` (not pulled by `scalameta`). It + `scalameta` are JVM-published on the 2.13 line → both consumed via `CrossVersion.for3Use2_13` (same as scalafix). Keeping the whole scalameta line on `_2.13` avoids suffix conflicts.
- **wartremover:** pinned 3.6.0 (latest) — 3.5.6 had no artifact for Scala 3.8.4.
- **sbt 2.0 test caching:** `Test / test` == cached `testQuick` — skips unchanged passing tests and reports "Total 0" even after `clean`. prePush uses `(Test / testOnly).toTask(" *")` to force the full suite.
- **Symbol grammar:** don't hand-parse — `scala.meta.internal.semanticdb.Scala.*` provides `owner`/`ownerChain`/`desc`/`isGlobal`/`isMethod`/… (same helpers Scalafix uses). `semanticdb-shared` also ships `metap.SymbolInformationPrinter` → reuse for Phase 3 signature rendering.