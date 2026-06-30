# Docs Content Prioritization & Strategic Shrink Plan

This plan defines the prioritization scoring, duplication resolution strategy, and file merge/cut decisions to guide the docs restructuring and shrinking phase (#157).

## 1. Prioritization & Budget Table

Each topic/file is scored from 1 (lowest) to 5 (highest) for the primary use cases:
- **Using**: Setup, integration, querying, and tool usage.
- **Understanding**: Architecture, design decisions, token cost comparison.
- **Contributing**: Development setup, testing, release flow, internals.
- **PR/Marketing**: Initial pitch, motivation, and conversion value.

| Topic/File | Using | Understanding | Contributing | PR/Marketing | Decision | Current Lines | Target Lines |
| :--- | :---: | :---: | :---: | :---: | :--- | :---: | :---: |
| [index.md](index.md) | 3 | 3 | 3 | 2 | **Keep** (clean TOC) | 32 | 20 |
| [getting-started/quickstart.md](getting-started/quickstart.md) | 5 | 2 | 2 | 4 | **Condense & Merge** | 45 | 15 |
| [getting-started/integration.md](getting-started/integration.md) | 5 | 3 | 4 | 3 | **Keep & Consolidate** | 143 | 100 |
| [getting-started/faq.md](getting-started/faq.md) | 4 | 4 | 2 | 3 | **Condense** | 35 | 20 |
| [explanation/scala-semantic-vs-grep.md](explanation/scala-semantic-vs-grep.md) | 3 | 5 | 2 | 5 | **Keep** | 72 | 50 |
| [reference/tools.md](reference/tools.md) | 5 | 3 | 4 | 3 | **Keep & Consolidate** | 71 | 60 |
| [usage/examples.md](usage/examples.md) | 5 | 3 | 3 | 4 | **Keep** (worked examples) | 188 | 150 |
| [project/design.md](project/design.md) | 1 | 5 | 5 | 2 | **Keep & Merge** | 39 | 45 |
| [project/development.md](project/development.md) | 1 | 3 | 5 | 1 | **Keep & Consolidate** | 55 | 50 |
| [project/releasing.md](project/releasing.md) | 1 | 1 | 5 | 1 | **Keep** | 60 | 40 |
| `project/opaque-types-design.md` | 1 | 4 | 3 | 1 | **Condense & Merge** | 39 | 0 (Delete) |
| `research/plan.md` | 1 | 2 | 3 | 1 | **Cut / Merge gotchas** | 41 | 0 (Delete) |
| `research/token-metrics.md` | 2 | 4 | 2 | 4 | **Condense & Merge** | 18 | 0 (Delete) |
| [research/token-metrics-methodology.md](research/token-metrics-methodology.md) | 2 | 5 | 3 | 4 | **Keep & Consolidate** | 193 | 150 |
| [research/claude-interaction-study.md](research/claude-interaction-study.md) | 2 | 4 | 3 | 3 | **Keep** | 76 | 60 |
| `research/llm-steering-investigation.md` | 2 | 4 | 3 | 2 | **Condense & Merge** | 29 | 0 (Delete) |
| [articles/it-hurts-to-watch-ai-grep-my-scala.md](articles/it-hurts-to-watch-ai-grep-my-scala.md) | 2 | 5 | 2 | 5 | **Keep** | 77 | 70 |
| [README.md](../README.md) | 5 | 4 | 3 | 5 | **Keep & Condense** | 74 | 50 |
| [AGENTS.md](../AGENTS.md) | 1 | 2 | 5 | 1 | **Keep & Condense** | 34 | 25 |
| **Total** | | | | | | **~1243** | **~795** |

---

## 2. Duplication Resolution Strategy

### a) Install / Setup steps
- **Canonical Home**: [getting-started/integration.md](getting-started/integration.md) (specifically `### Option A — sbt plugin`).
- **Resolution**:
  - Keep the full sbt block in `integration.md`.
  - In [getting-started/quickstart.md](getting-started/quickstart.md) and [README.md](../README.md), replace the redundant multi-line explanations/links with a minimal snippet + a direct link to `integration.md` for advanced options or issues.
  - In [articles/it-hurts-to-watch-ai-grep-my-scala.md](articles/it-hurts-to-watch-ai-grep-my-scala.md), use a reference pointer/link to `integration.md` rather than maintaining its own static setup code block.

### b) Tool descriptions
- **Canonical Home**: [reference/tools.md](reference/tools.md) (names, purposes, schema rules).
- **Resolution**:
  - Keep the concise tools table in `tools.md`. 
  - Remove the generic redundant JSON-RPC request example from `tools.md` and link directly to [usage/examples.md](usage/examples.md) for full worked JSON payloads.

### c) Token metrics split
- **Canonical Home**: [research/token-metrics-methodology.md](research/token-metrics-methodology.md).
- **Resolution**:
  - Delete `research/token-metrics.md`.
  - Fold the auto-generated metrics table solely into `token-metrics-methodology.md` under the auto-generated comment hooks. Let the metrics test suite update the methodology file directly.

---

## 3. Strategic Shrink Plan

### a) File Merges & Deletions
1. **Opaque Types Design** -> Merge into [project/design.md](project/design.md):
   - Condense `project/opaque-types-design.md` into a single paragraph summary under `## Opaque Types Decision` in `design.md`.
   - Delete `docs/project/opaque-types-design.md`.
2. **LLM Steering Investigation** -> Merge into [project/design.md](project/design.md):
   - Move key findings of `research/llm-steering-investigation.md` as design rationale under a new `## Agent Steering Strategy` section in `design.md`.
   - Delete `docs/research/llm-steering-investigation.md`.
3. **Research Plan** -> Cut and Merge:
   - Move "Known issues / gotchas" from `research/plan.md` to [project/development.md](project/development.md) under `## Build & test gotchas`.
   - Delete `docs/research/plan.md` as its phase tracking is stale and historical.

### b) README.md vs AGENTS.md
- **README.md**:
  - Keep it high-level, marketing-thin, and conversion-focused.
  - Retain the quick setup, core value proposition (vs grep table), and links to other documentation folders.
  - Avoid detailed commands, config variants, or deep internals.
- **AGENTS.md**:
  - Keep it focused only on agent instructions: workspace rules, branches, tree2m commands, and compilation sequence.
  - Link to `development.md` for manual compilation/test detailed variants.
