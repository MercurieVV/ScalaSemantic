# ScalaSemantic — Plan & Execution Tracker

Living doc. Status: ⬜ todo · 🔄 in-progress · ✅ done · ⛔ blocked

## Goal

MCP server doing deep semantic analysis on Scala via SemanticDB — beyond Metals/LSP.

## Phases

| # | Phase | Status |
|---|-------|--------|
| 0 | Setup (CLAUDE.md, prePush, configs) | ✅ |
| 1 | SemanticDB study + symbol-grammar helpers | ✅ |
| 2 | Result models (upickle case classes) | ✅ |
| 3 | Analysis engine — 9 query methods | ✅ |
| 4 | MCP protocol layer (stdio JSON-RPC, tool registry) | ✅ |
| 5 | Tests: dogfood + SemanticDB-vs-grep docs | ✅ |

All 9 query tools shipped: `find_usages`, `method_signature`, `class_hierarchy`, `resolve_implicits`, `type_at_position`, `find_overloads`, `members`, `trace_implicit_chain`, `call_path`.

## Architecture decisions (shipped)

- **3 layers:** `mcp` → `analysis` → `core`. `core` has no JSON/MCP deps. Dogfood tests load `SemanticIndex.fromProject(".")` to query the repo's own SemanticDB.
- **stdio process model:** MCP clients spawn the server; no daemon. Portability via `java -jar`; sbt plugin shells out (avoids Scala 3.8.4 meta-build mismatch, keeps sbt-1/2 compatibility).
- **Lean-by-default responses:** `uri:line:col` locations, one-line signatures, empty fields omitted. `"detailed": true` and `"include": [...]` opt in to richer output.
- **sbt plugin:** `io.github.mercurievv:sbt-scalasemantic-mcp` (AutoPlugin, opt-in). Sets `semanticdbEnabled`, provides `mcpClientConfig`/`mcpRun`. Not aggregated into root.
- **Publishing:** `sbt-ci-release` 1.11.2 on sbt 2.0; namespace `io.github.mercurievv`; version from sbt-dynver tags; publishes `core`, `analysis`, `mcp`, `sbt-plugin`.

## Backlog

- **`reload` MCP tool:** re-read `*.semanticdb` from disk without restarting the server. Pairs with `sbt ~compile` for near-live analysis.
- **HTTP/SSE transport:** enables a real background daemon with `mcpServerStart`/`Stop` instead of client-spawned stdio.

## Known issues / gotchas

- **sbt 2.0.0 API:** `test` is now `InputKey` → use `(Test / test).toTask("")`; task returning `File` needs `Def.uncached`.
- **Meta-build conflict:** sbt 2.0 meta-build is Scala 3; plugins drag `_2.13` scala-collection-compat → `ConflictWarning.disable` in `project/plugins.sbt`.
- **SemanticDB bindings:** `scala.meta.internal.semanticdb.*` is in `semanticdb-shared` (not in `scalameta`). Both consumed via `CrossVersion.for3Use2_13`.
- **sbt 2.0 test caching:** `Test / test` == cached `testQuick`. `prePush` uses `(Test / testOnly).toTask(" *")` to force the full suite.
- **wartremover:** pinned 3.6.0 — 3.5.6 had no artifact for Scala 3.8.4.
