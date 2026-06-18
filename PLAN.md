# ScalaSemanticMCP — Plan & Execution Tracker

Living doc. Update status as work lands. Status: ⬜ todo · 🔄 in-progress · ✅ done · ⛔ blocked

## Goal
MCP server doing deep semantic analysis on Scala via SemanticDB — beyond Metals/LSP.

## Architecture
Three sbt modules, one per layer: `mcp` → `analysis` → `core`.
- `core` (…​.semanticdb): SemanticIndex load/index — no JSON, no MCP.
- `analysis` (…​.analysis, …​.model): Analyzer + upickle models; dependsOn core.
- `mcp` (…​.mcp, Main): stdio JSON-RPC server; dependsOn analysis (`test->test` so fixtures compile first).
- Dogfood tests load `fromProject(".")` (repo root) to see every module's SemanticDB.

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
| 5 | Tests: dogfood + Metals comparison | ✅ | 33 tests; docs/COMPARISON.md + README.md |

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
- Transport: newline-delimited JSON-RPC 2.0 over stdio (`Mcp.serve`); pure `Mcp.handle`/`Mcp.process` for testing. Run: `runMain com.github.mercurievv.scalasemantic.mcpServer <root>`.
- Token discipline (per request): lean by default — locations as `uri:line:col`, signatures as one rendered line, related symbols as display names; empty fields omitted. `"detailed": true` opts into structured breakdowns; `find_usages` is paged (`limit`/`offset` + `referenceCount`).
- 9 tools: find_usages, method_signature, class_hierarchy, find_overloads, members, type_at_position, resolve_implicits, trace_implicit_chain, call_path.

## Integration / launch (build-tool wiring)
- **Lifecycle decision:** stdio MCP servers are spawned by the client (Claude Code), which owns start/stop. So "a service per project" = a registered launch command + emitted SemanticDB, not a daemon. A true start/stop daemon would need an HTTP/SSE transport (backlog).
- **Portability via process launch:** the unit is `java -cp … mcpServer <root>`. An sbt plugin can't link the Scala 3.8.4 server (meta-build Scala mismatch) — it shells out, which is also what makes it sbt-1/2 and Mill/Gradle/CLI portable.
- **`mcp/mcpLauncher`** task → writes a clean-stdout launcher script (resolved classpath via sbt 2.0 `fileConverter`; `Def.uncached` since it returns a File). **`mcp/mcpClientConfig`** → prints the `.mcp.json` entry. Verified end-to-end over real stdio (clean JVM).
- **`sbt-plugin`** module = `com.github.mercurievv:sbt-scalasemantic-mcp` (AutoPlugin, opt-in). Sets `semanticdbEnabled`, provides `mcpClientConfig`/`mcpRun`. Not aggregated into `root` (built against the sbt-plugin Scala). Verified in a throwaway host project: enable → compile → server analyzed the host's SemanticDB.

## Backlog
- **`reload` MCP tool** (later): re-read `*.semanticdb` from disk without restarting the server (re-run `SemanticIndex.fromProject`). SemanticDB only updates on compile, and the server loads the index once at startup — a reload tool pairs with `sbt ~compile` for near-live analysis.
- **HTTP/SSE transport + daemon** (later): enables a real start/stop background service (`mcpServerStart`/`Stop` with a pidfile) instead of client-spawned stdio.
- **sbt 1 cross-publish** of the plugin (`^ publishLocal`) and a published server jar so `mcpServerCommand` can default to a resolved artifact.

## Known issues / decisions
- **sbt 2.0.0 API shifts:** `test` is now an `InputKey` → use `(Test / test).toTask("")`; task result caching needs a `HashWriter` → wrap aggregate task in `Def.uncached(...)`.
- **Meta-build conflict:** sbt 2.0 meta-build is Scala 3; plugins drag `_2.13` scala-collection-compat → `ConflictWarning.disable` in `project/plugins.sbt`.
- **SemanticDB bindings:** `scala.meta.internal.semanticdb.*` lives in artifact `semanticdb-shared` (not pulled by `scalameta`). It + `scalameta` are JVM-published on the 2.13 line → both consumed via `CrossVersion.for3Use2_13` (same as scalafix). Keeping the whole scalameta line on `_2.13` avoids suffix conflicts.
- **wartremover:** pinned 3.6.0 (latest) — 3.5.6 had no artifact for Scala 3.8.4.
- **sbt 2.0 test caching:** `Test / test` == cached `testQuick` — skips unchanged passing tests and reports "Total 0" even after `clean`. prePush uses `(Test / testOnly).toTask(" *")` to force the full suite.
- **Symbol grammar:** don't hand-parse — `scala.meta.internal.semanticdb.Scala.*` provides `owner`/`ownerChain`/`desc`/`isGlobal`/`isMethod`/… (same helpers Scalafix uses). `semanticdb-shared` also ships `metap.SymbolInformationPrinter` → reuse for Phase 3 signature rendering.