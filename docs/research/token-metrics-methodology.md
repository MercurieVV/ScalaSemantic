# Token-Cost Methodology: ScalaSemantic MCP vs. Raw File/Grep Context

## Purpose

LLM agents pay for every token they read. When an agent needs to answer a Scala
symbol question (e.g. "what are the subtypes of `Animal`?"), it has two options:

1. **Grep / read files** — pull raw source text into the context window and let
   the model scan it.
2. **Use a ScalaSemantic MCP tool** — call a focused tool that returns only the
   compiler-resolved answer.

The structured tool answer is almost always far smaller than the raw source that
would otherwise be consumed. This page documents the measurement methodology,
shows how to reproduce the numbers, and interprets the results.

---

## Methodology

### Token proxy

Exact token counts depend on the tokeniser of each LLM, and tokenisers differ
across model families. To keep the benchmark model-agnostic and fully
reproducible without requiring a running model, we use a **character-count
proxy**:

```
approxTokens(text) = ceil(length(text) / 4)
```

This matches the widely-used rule of thumb for English / code text. The same
formula is applied identically to both the tool path and the baseline path, so
any systematic bias cancels out when comparing the two.

### Tool path

The **tool path** is the exact JSON text that the MCP tool renderer returns to
the agent for a given query. No surrounding envelope, no extra explanation — just
the response payload.

### Baseline path

The **baseline path** is the raw context an agent would need to inspect if the
tool did not exist:

| Query type | Baseline context |
|---|---|
| `find_usages` (cross-file) | Concatenated `grep` hits for the symbol name across all fixture `.scala` sources |
| `class_hierarchy`, `method_signature`, `trace_implicit_chain`, `call_path` | Full text of the fixture source file that defines the relevant types/methods, prefixed with a one-line reason comment |

Both pieces of context are generated deterministically from **checked-in fixture
sources** — no network calls, no runtime environment dependency. The numbers are
therefore stable across machines and CI runs.

### Fixture sources

Queries target symbols from the project's own test fixtures located at:

- `analysis/src/test/scala/com/github/mercurievv/scalasemantic/fixtures/`
- `compat-fixtures/src/main/scala-3/`

Key symbols used:

| Symbol | SemanticDB path |
|---|---|
| `Animal` trait | `com/github/mercurievv/scalasemantic/fixtures/Animal#` |
| `Sample.render` method | `com/github/mercurievv/scalasemantic/fixtures/Sample.render().` |
| `Show` trait | `com/github/mercurievv/scalasemantic/fixtures/Show#` |
| `Calls.a` / `Calls.c` methods | `com/github/mercurievv/scalasemantic/fixtures/Calls.a().` etc. |

---

## Queries measured

Five representative queries span the most common MCP tool families:

| # | Query | Tool | Baseline strategy |
|---|---|---|---|
| 1 | Find all definitions and references of the `Animal` trait | `find_usages` | Text grep for `Animal` across all fixture source trees |
| 2 | Identify `Animal` parents, linearization, and known subtypes | `class_hierarchy` | Read the fixture source containing `Animal`, `Dog`, and `Fish` inheritance |
| 3 | Recover `Sample.render`'s full signature, including `using` parameters | `method_signature` | Read the fixture source and infer the complete signature from text |
| 4 | Trace givens that produce `Show` and their dependencies | `trace_implicit_chain` | Read the fixture source and follow given definitions manually |
| 5 | Find the call path from `Calls.a` to `Calls.c` | `call_path` | Read the fixture source and follow method bodies manually |

---

## Results

The auto-generated table below is kept in sync with
[`docs/research/token-metrics.json`](token-metrics.json) by the test suite (see
[How to reproduce](#how-to-reproduce)). Do not edit it by hand.

<!-- BEGIN AUTO-GENERATED: see docs/research/token-metrics.md -->

| Query | MCP tool | Tool tokens | Baseline tokens | Delta | Savings |
| --- | --- | ---: | ---: | ---: | ---: |
| `find-usages-animal` | `find_usages` | 100 | 2810 | 2710 | 96.4% |
| `class-hierarchy-animal` | `class_hierarchy` | 111 | 380 | 269 | 70.8% |
| `method-signature-render` | `method_signature` | 83 | 379 | 296 | 78.1% |
| `trace-implicit-show` | `trace_implicit_chain` | 56 | 379 | 323 | 85.2% |
| `call-path-a-to-c` | `call_path` | 65 | 378 | 313 | 82.8% |
| **Overall (5 queries)** | | **415** | **4326** | **3911** | **90.4%** |

<!-- END AUTO-GENERATED -->

The canonical source of truth for these numbers is
[`docs/research/token-metrics.json`](token-metrics.json).

---

## How to reproduce

### Prerequisites

The project must have been compiled at least once so that SemanticDB output
exists under each module's `target/` directory:

```bash
sbt compile
```

### Run in verification mode (default)

The test suite reads the checked-in JSON and Markdown artifacts and asserts they
match the freshly-computed values. Any drift (e.g. after adding a new fixture
symbol) is reported as a test failure:

```bash
sbt "mcp/testOnly com.github.mercurievv.scalasemantic.mcp.TokenMetricsSuite"
```

### Regenerate artifacts

Set `UPDATE_TOKEN_METRICS=1` to overwrite both
`docs/research/token-metrics.json` and `docs/research/token-metrics.md` in place:

```bash
UPDATE_TOKEN_METRICS=1 sbt "mcp/testOnly com.github.mercurievv.scalasemantic.mcp.TokenMetricsSuite"
```

After regeneration, commit both updated files together.

### Test file location

The full test implementation (query definitions, baseline generators, token
counter, JSON and Markdown renderers) is at:

[`mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/TokenMetricsSuite.scala`](../../mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/TokenMetricsSuite.scala)

---

## Interpretation

### Why the `find_usages` savings (96.4%) are so high

A cross-file usage query via grep must concatenate matching lines from every
source file in the search tree. Even with a relatively simple symbol like
`Animal`, that surfaces dozens of lines across multiple files — 2 810 proxy
tokens versus 100 for the structured response. The tool returns only the
compiler-resolved occurrences (file, line, column, role), with zero surrounding
noise.

### Why structural-query savings (70–85%) are lower but still substantial

For `class_hierarchy`, `method_signature`, `trace_implicit_chain`, and
`call_path`, the baseline is a single file read rather than a multi-file grep.
The gap narrows, but the MCP response is still 4–5× smaller because it strips
all source lines that are irrelevant to the specific question.

### Practical impact on agent context budgets

With an overall savings rate of **90.4%** across 5 queries:

- A context budget that sustains ~4 raw-file reads can instead support ~44
  structured queries.
- For agents that iterate across many symbols (e.g. exploring a call graph or
  resolving an implicit chain), this compounds rapidly — each hop now costs
  ~65–111 tokens instead of ~380–2 810.
- Smaller per-query context also means the model receives less distracting
  content, which improves answer quality independently of token cost.

### Limitations

- The 1/4-character approximation is a lower-bound proxy. Real tokenisers
  typically produce more tokens for code (operators, identifiers, punctuation)
  than for prose, so actual savings may be larger.
- Baselines assume the agent reads the minimum sufficient context. A real agent
  that reads multiple candidate files before finding the right one would show
  even larger baseline token counts.
- The fixture corpus is small. Savings on cross-file queries like `find_usages`
  scale with codebase size; in a large monorepo the baseline grep context grows
  proportionally while the tool output stays compact.
