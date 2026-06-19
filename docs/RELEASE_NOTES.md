# Release notes

User-facing changes per release, newest first. Versions are git tags (`vX.Y.Z`); pushing a tag
publishes `io.github.mercurievv:scalasemantic-*` to Maven Central. The release *process* lives in
[Releasing](RELEASING.md) — this page is the *what changed*.

## v0.2.0 — 2026-06-19

**Presentation-compiler backend: answer about uncompiled / broken buffers.**

The disk SemanticDB the server indexes only exists after a clean compile, so a file edited since —
or that never — compiled had no data. This release adds Scala 3's in-tree presentation compiler as
a second backend that regenerates SemanticDB for a single buffer in memory, tolerant of parse/type
errors.

- New `pc` module wrapping `scala3-presentation-compiler_3` (version-locked to the compiler).
- `type_at_position` and `method_signature` accept the file's current `source` to answer on a live
  buffer; without it they read the last compiled SemanticDB as before.
- Tools are split into three backend categories — **PC-only** (`type_at_position`), **overlay**
  (`method_signature`), **index-only** (the eight project-wide tools) — so the PC is used where it
  is authoritative, not as a blanket fallback.
- Server gains a classpath argument (CLI, file, or `SCALASEMANTIC_CLASSPATH`) that enables the PC
  backend; the sbt plugin emits the project compile classpath (`mcpClasspathFile`) and the launcher
  scripts forward it. Without a classpath the server is index-only and `source` is ignored.
- Docs: checked examples page, FAQ + integration updates.

## v0.1.6 — 2026-06-19

- `find_symbol`/`find_usages` gain `kind`, `exact`, `pathFilter`, section `include`, and occurrence
  dedup to cut tokens and scope results.
- mdoc + Docusaurus documentation microsite, published to GitHub Pages on `master`; SEO metadata
  (social card, JSON-LD, sitemap, robots).
- Runtime `BuildInfo` version (server reports its real published version).
- Cross-version compatibility tests against regenerated golden SemanticDB.

## v0.1.5 — 2026-06-19

- Standalone fat jar attached to GitHub Releases, plus self-bootstrapping, coursier-first launcher
  scripts — run the server with no prior install.

## v0.1.0–v0.1.4 — 2026-06-18

Initial public releases.

- The MCP stdio JSON-RPC server with the analysis tools (find-usages, method-signature,
  class-hierarchy, find-overloads, members, type-at-position, resolve-implicits,
  trace-implicit-chain, call-graph-path) over compiler-emitted SemanticDB.
- Three sbt modules (`core`, `analysis`, `mcp`) and an opt-in sbt plugin for per-project wiring.
- Maven Central publishing via `sbt-ci-release` on tag push.
- v0.1.1–v0.1.4 were release-pipeline fixes (gpg import, ci-release step) with no library changes.
