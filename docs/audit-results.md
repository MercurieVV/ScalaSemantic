# Documentation Audit — Catalog & Duplication Analysis

This audit catalogs all 19 Markdown doc files in ScalaSemantic (`docs/`, `README.md`, `AGENTS.md`)
ahead of a planned shrinking/restructuring pass. It classifies each file by Diátaxis bucket and
use case, then flags cross-file duplication (install steps, tool descriptions, token-metrics
split) and assesses staleness of the two research/backlog files.

## Catalog

| Path | Lines | Diátaxis bucket | Purpose (one line) | Use cases |
|---|---:|---|---|---|
| docs/index.md | 32 | getting-started | Top-level doc map / table of contents linking all other docs | using, understanding, contributing |
| docs/getting-started/quickstart.md | 45 | getting-started | Shortest sbt-project path: plugin, config gen, compile, first query | using |
| docs/getting-started/integration.md | 143 | getting-started | All install paths (sbt plugin / launcher / plain jar), client config shapes, logging, manual stdio check | using, contributing |
| docs/getting-started/faq.md | 35 | getting-started | Q&A on rationale (why SemanticDB), compile freshness, Metals comparison, install choice | using, understanding |
| docs/explanation/scala-semantic-vs-grep.md | 72 | explanation | When to use ScalaSemantic vs grep, measured token costs, limitations, Metals comparison | understanding |
| docs/reference/tools.md | 71 | reference | Full tool list with one-line purpose, symbol grammar, raw JSON-RPC request shape | using, contributing |
| docs/usage/examples.md | 188 | usage | Worked request/response JSON for each tool against one shared fixture, with grep contrast per example | using |
| docs/project/design.md | 39 | project | Architecture layers and key design choices/tradeoffs, future plugin-SPI idea | understanding, contributing |
| docs/project/development.md | 55 | project | Module layout, build/test commands, cross-version test setup, docs-site build, sbt 2.0 gotchas | contributing |
| docs/project/releasing.md | 60 | project | Sonatype Central release process, secrets, scripts, release-notes generation | contributing |
| docs/project/opaque-types-design.md | 39 | project | Issue #69 candidate survey for opaque types; recommends no further wrapping | understanding, contributing |
| docs/research/plan.md | 41 | research | Living phase tracker, shipped architecture decisions, backlog, known gotchas | contributing, understanding |
| docs/research/token-metrics.md | 18 | research | Auto-generated summary table of token-savings measurements (5 queries) | understanding |
| docs/research/token-metrics-methodology.md | 193 | research | Full methodology, fixture sources, reproduction steps, and interpretation behind the token-metrics numbers | understanding, contributing |
| docs/research/claude-interaction-study.md | 76 | research | Data-driven study of how Claude Code actually used the tools (transcript mining); drove `document_outline`/`rename_plan` | understanding, contributing |
| docs/research/llm-steering-investigation.md | 29 | research | Investigation of how to steer agents toward ScalaSemantic instead of grep across clients (issue #56) | understanding, contributing |
| docs/articles/it-hurts-to-watch-ai-grep-my-scala.md | 77 | articles | Narrative/blog-style motivation piece for the project, with mini setup snippet | understanding |
| README.md | 74 | getting-started (overview) | Repo landing page: pitch, quick install, tool table, grep comparison, doc links | using, understanding |
| AGENTS.md | 34 | project (contributing — agent instructions) | Agent-facing quick-start: stack, module layout, commands, worktree/PR flow, pointer to CLAUDE.md | contributing |

Notes on ambiguous classifications:
- **README.md** is classified `getting-started (overview)` rather than plain `reference` — its
  content is almost entirely a condensed quickstart + pitch (install snippet, tool table, grep
  comparison) that exists to get a new visitor from "what is this" to "first query," which is the
  defining trait of a getting-started/landing doc, not a Diátaxis reference page.
- **AGENTS.md** is classified `project (contributing)` — it is not end-user-facing documentation at
  all; it's operational instructions for coding agents (build commands, worktree flow, module
  defaults), which is the same audience/purpose as `docs/project/development.md`.

## Duplication & Overlap Findings

### a) Install/setup steps — README.md vs AGENTS.md vs docs/getting-started/integration.md

- **README.md `## Quick install (sbt)`** (lines 12–26) and **docs/getting-started/quickstart.md
  `## 1. Add the sbt plugin` / `## 2. Generate MCP config...`** (lines 5–23) and
  **docs/getting-started/integration.md `### Option A — sbt plugin (recommended)`** (lines 30–48)
  all contain **near-verbatim duplicate** code blocks:
  ```scala
  addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
  enablePlugins(ScalaSemanticMcpPlugin)
  ```
  This exact plugin snippet appears **three times** (README.md L14-18, quickstart.md L7-12,
  integration.md L32-37), plus a fourth near-variant with a literal `x.y.z` instead of
  `@VERSION@` in `docs/articles/it-hurts-to-watch-ai-grep-my-scala.md` (`## Setup for an sbt
  project`, L61-66). The Maven Central / GitHub Releases links describing `@VERSION@` are also
  repeated verbatim in quickstart.md (L14) and integration.md (L39).
- `sbt mcpClientConfig` / `sbt compile` two-liner appears in README.md (L21-24), quickstart.md
  (L18-21, with the `all` variant added), and the article (L68-71) — same content, different
  framing each time.
- **AGENTS.md** does *not* duplicate the plugin-install snippet; instead it has its own
  agent-specific "Core Commands" section (L19-24: `sbt --error compile`, `sbt --error test`,
  `sbt prePush`, `mcp/runMain`, `tree2m`) that is a distinct (and itself overlapping) duplicate of
  **docs/project/development.md `## Build & test`** (L17-30) and parts of the root `CLAUDE.md`
  "Build / test" section — not of the end-user install flow. So AGENTS.md duplication is
  build/test-command duplication with development.md, not install-step duplication with
  README/integration.
- **Overall assessment**: the plugin-install two-block snippet (plugins.sbt + build.sbt) is
  copy-pasted near-verbatim across 4 files (README, quickstart, integration, article). Only
  integration.md adds genuinely file-specific content beyond it (Options B/C, client-config table,
  logging flags, manual stdio check — L50-143). quickstart.md and README's versions are subsets
  with no unique install content; they exist purely to route the reader to integration.md for
  anything beyond sbt Option A. This is intentional layering (overview → detail) per Diátaxis, but
  the literal code block is real, byte-for-byte duplication that would drift if the plugin
  coordinate or flag set ever changes (it already required updating 4 places).

### b) Tool descriptions — docs/reference/tools.md vs docs/usage/examples.md

- **tools.md** (`## Tools` table, L7-24) gives one row per tool: name + one-line purpose, plus the
  symbol-grammar table and a generic raw JSON-RPC request shape (L43-69) using `find_symbol` →
  `class_hierarchy` as the example pair.
- **examples.md** covers a *subset* of the same tools (`find_symbol`, `find_usages`,
  `class_hierarchy`, `method_signature`, `resolve_implicits`, `call_path`, `type_at_position` — 7
  of the ~14 tools listed in tools.md) with full worked request/response JSON pairs against one
  shared fixture (`Animal`/`Dog`/`Fish`/`Show`/`Sample`/`Calls`, defined once at the top, L5-28),
  plus a "With grep: ..." one-liner contrasting each.
- **Overlap is genuinely complementary, not redundant**: tools.md is the lookup table (what exists,
  what it's for); examples.md is worked execution (what calling it actually looks like end-to-end).
  The only literal duplication is the `class_hierarchy` request shape: tools.md's generic example
  (L59-69, symbol `com/example/Animal#`) and examples.md's `## Ask for class hierarchy` example
  (L72-94, same tool, same shape, different fixture path) are structurally identical JSON — this is
  minor and arguably fine since tools.md explicitly defers to examples.md ("See Examples for full
  request/response pairs for every tool", L71) immediately after showing its own two-call generic
  illustration. tools.md's own embedded examples (L47-69) are largely superseded by examples.md and
  could be trimmed to a single call without losing information, since examples.md already covers
  `find_symbol`→`class_hierarchy` in more detail.
- **Gap**: examples.md does not cover `find_overloads`, `members`, `trace_implicit_chain` (only
  appears in token-metrics, not examples.md... actually trace_implicit_chain is NOT in examples.md
  despite being in tools.md), `annotated_source`, `document_outline`, `rename_plan`, `move_plan`,
  `extract_method_plan` — 7 of 14 tools in tools.md have no worked example. Not duplication, but
  worth flagging as a coverage gap discovered during this audit.

### c) Token metrics split — token-metrics.md vs token-metrics-methodology.md

- **token-metrics.md** (18 lines) is purely the **auto-generated results table** (5 queries, tool
  vs baseline tokens, savings %) plus the one-line regeneration command. It is explicitly a build
  artifact: "Regenerate with `UPDATE_TOKEN_METRICS=1 sbt ...`".
- **token-metrics-methodology.md** (193 lines) contains the **same exact table verbatim**, embedded
  between `<!-- BEGIN AUTO-GENERATED -->` / `<!-- END AUTO-GENERATED -->` markers (L94-105) —
  identical numbers to token-metrics.md — plus everything token-metrics.md lacks: purpose,
  token-proxy formula, tool-path/baseline-path definitions, fixture source locations, per-query
  query descriptions, reproduction steps, and an "Interpretation" section explaining *why* the
  numbers look the way they do.
- **Assessment**: the table duplication (rows are byte-identical) is acknowledged in the file
  itself — methodology.md says "kept in sync with `docs/research/token-metrics.json`... by the test
  suite" and "Do not edit it by hand," i.e. it's deliberately regenerated/embedded, not manually
  duplicated. The split is **justified**: token-metrics.md is the at-a-glance number (linked from
  scala-semantic-vs-grep.md L53 as "More per-query measurements"), methodology.md is the full
  writeup for anyone who wants to trust or reproduce the numbers. However, having the full table
  embedded twice (not just summarized/linked) in two committed files that must stay in sync via a
  test suite is a real coupling cost — a future shrink could make token-metrics.md link to the
  table in methodology.md instead of duplicating it, or vice versa, collapsing to one generated
  artifact + one prose file that includes it by reference.

## Stale Content

- **docs/research/plan.md** — **largely stale/superseded but harmless as a historical log.** All 5
  phases in its tracker table are marked `✅` done (L13-18) and the architecture-decisions section
  (L22-28) matches what's now stated as fact (not plan) in docs/project/design.md and
  docs/project/development.md. The "Backlog" section (L30-33) lists a `reload` MCP tool and
  HTTP/SSE transport as unimplemented — these do **not** appear in the current shipped tool list in
  reference/tools.md (14 tools, no `reload`), so the backlog items are still genuinely open/future
  work, not stale claims. The "Known issues / gotchas" section (L35-41) is sbt/build-environment
  trivia that duplicates content already captured in docs/project/development.md's "sbt 2.0
  gotchas" (L49-53) almost one-for-one (test-caching note, `Def.uncached`, semanticdb-shared
  artifact). **Verdict**: the phase tracker and decisions sections are stale in the sense of
  "already true, no longer 'planned'" — this file reads as a project-history/changelog snapshot
  more than a live plan, and its gotchas content is now duplicated, not unique. The backlog two
  items remain accurate as open work.
- **docs/research/llm-steering-investigation.md** — **still current, not stale.** It explicitly
  references issue #56 and recommends (L24-29) the two-pronged approach (`instructions` field +
  `SCALA_SEMANTIC_RULES.md` generation) that is exactly what's implemented today per
  quickstart.md L16-23 and integration.md's client-config section — `sbt mcpClientConfig` does
  generate `SCALA_SEMANTIC_RULES.md` and reference it from client configs, matching the
  recommendation verbatim. No TODO markers, no contradiction with the current tool list (this file
  isn't about tools at all, it's about steering mechanism). The closing line "Already implemented"
  (L29) is accurate and dates the doc as a completed-investigation record rather than an open
  question. **Verdict**: current/accurate, functions as design rationale rather than a backlog —
  could be merged into design.md as a "steering" subsection in a future pass, but its content is not
  outdated.
