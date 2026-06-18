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
| 1 | SemanticDB study + symbol-grammar helpers (fix `owner` dead code) | ⬜ | |
| 2 | Result models (upickle case classes) | ⬜ | |
| 3 | Analysis engine — query methods | ⬜ | see method list |
| 4 | MCP protocol layer (JSON-RPC, tool registry) | ⬜ | |
| 5 | Tests: dogfood + Metals comparison | ⬜ | |

## Phase 3 — query methods (one MCP tool each)
- ⬜ find-usages (cross-file references)
- ⬜ method-signature (incl. implicit params)
- ⬜ class-hierarchy / trait relationships
- ⬜ resolve-implicits for a type
- ⬜ trait-vs-local members
- ⬜ type-at-position
- ⬜ find-overloads
- ⬜ trace-implicit-chain
- ⬜ call-graph + path-find between methods

## Setup checklist (Phase 0)
- ✅ CLAUDE.md
- ✅ project/plugins.sbt (scalafix, scalafmt, wartremover)
- ✅ .scalafmt.conf, .scalafix.conf
- ✅ build.sbt: prePush task, wart warnings, semanticdb
- ✅ resolve meta-build cross-version conflict (`conflictWarning := disable` in plugins.sbt)
- ✅ verify `sbt prePush` runs green
- ⬜ initial git commit

## Known issues / decisions
- **sbt 2.0.0 API shifts:** `test` is now an `InputKey` → use `(Test / test).toTask("")`; task result caching needs a `HashWriter` → wrap aggregate task in `Def.uncached(...)`.
- **Meta-build conflict:** sbt 2.0 meta-build is Scala 3; plugins drag `_2.13` scala-collection-compat → `ConflictWarning.disable` in `project/plugins.sbt`.
- **SemanticDB bindings:** `scala.meta.internal.semanticdb.*` lives in artifact `semanticdb-shared` (not pulled by `scalameta`). It + `scalameta` are JVM-published on the 2.13 line → both consumed via `CrossVersion.for3Use2_13` (same as scalafix). Keeping the whole scalameta line on `_2.13` avoids suffix conflicts.
- **wartremover:** pinned 3.6.0 (latest) — 3.5.6 had no artifact for Scala 3.8.4.