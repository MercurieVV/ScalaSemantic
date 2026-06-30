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

## Scope: two distinct measurements

This document defines two related but distinct measurements. Keep them
separate in any report, talk, or follow-up issue — they answer different
questions and use different counting methods:

1. **Per-call output size** ([Part 1](#part-1-per-call-output-size-implemented),
   implemented today). "How big is the answer to one MCP tool call versus the
   equivalent raw grep/file context?" This is what PR #95 and
   `TokenMetricsSuite` measure, using the character-count proxy defined below
   against a fixed, checked-in fixture corpus. It needs no running model and
   re-runs deterministically in CI.
2. **End-to-end agent context cost** ([Part 2](#part-2-end-to-end-agent-context-cost-methodology-for-the-live-run)),
   methodology only — the harness and the live run are left to later
   subtasks. "How many tokens does a real agent (claude-cli / codex / agy)
   actually spend completing a realistic task, WITH ScalaSemantic MCP tools
   available versus WITHOUT them (grep + file reads + LSP/Metals-style
   navigation only)?" This requires running an actual engine against a live
   project and reading real token-usage accounting from that engine, not a
   character proxy.

Both parts use the **same underlying task set** (find-usages,
class-hierarchy, method-signature, resolve-implicits/trace-implicit-chain,
call-path, plus multi-step tasks — see [Queries measured](#queries-measured)
and [Multi-step tasks](#multi-step-tasks)), so results are comparable side by
side, but the two numbers are **not interchangeable**: Part 1 isolates the
tool-response payload; Part 2 captures the full agent loop (system prompt,
tool-call overhead, intermediate reasoning, retries, and whatever files the
agent chooses to read on its own). Part 2 totals are expected to be larger in
absolute terms than Part 1, but should show comparable or larger relative
savings, since an agent without semantic tools tends to over-read files and
re-grep when its first guess misses.

---

## Part 1: Per-call output size (implemented)

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

This is the single-call task set from PR #95, unchanged for continuity. The
underlying issue (#136) also names `resolve-implicits` as a representative
question family; today's fixture set covers it via the closely related
`trace_implicit_chain` query (`trace-implicit-show`). When the live-run harness
is built (Part 2), add a dedicated `resolve-implicits-show` query using the
`resolve_implicits` tool alongside `trace-implicit-show`, so both tools in
that family are represented without renaming or dropping the existing
`trace-implicit-show` id (renaming would break the JSON schema's `id` field
and silently invalidate historical comparisons).

### Multi-step tasks

Single-call queries measure one tool invocation. Real agent work is rarely
one call — an agent typically chains several lookups to answer a question
like "is it safe to rename this method?". Part 2's live run must therefore
also include 2-3 **multi-step tasks**, each requiring multiple tool/grep
calls chained together, run end-to-end through an actual engine:

| # | Task | Representative call chain (WITH MCP) | Representative call chain (WITHOUT MCP) |
|---|---|---|---|
| M1 | "Is it safe to rename `Animal` to `Creature`? List every call site that would need to change." | `find_usages(Animal)` → `rename_plan(Animal, Creature)` | grep `Animal` across the tree → open and read every matching file → manually enumerate edit sites |
| M2 | "Trace how `Show` instances reach `Sample.render`, including every given in the chain." | `method_signature(Sample.render)` → `trace_implicit_chain(Show)` → `resolve_implicits(Sample.render)` | read `Sample.scala` in full → grep for `given`/`Show` across the tree → manually trace dependency chain |
| M3 | "Does `Calls.a` ever reach `Calls.c`, and if so list the intermediate call sites?" | `call_path(Calls.a, Calls.c)` | read the file(s) defining `Calls` → manually trace method bodies → grep callers of each intermediate method to confirm no shortcut path exists |

Each multi-step task is scored the same way as a single-call query (tool
tokens vs. baseline tokens, delta, % savings), but the "tool tokens" side
sums every call in the chain, and the "baseline tokens" side sums every grep
invocation and file read the WITHOUT-MCP path required. Multi-step tasks are
expected to show the largest relative savings, because baseline cost grows
roughly linearly with the number of hops while structured-tool cost grows
much more slowly (each hop returns a compact, targeted answer instead of
another full file).

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

---

## Part 2: End-to-end agent context cost (methodology for the live run)

This part defines how the **live run** subtask(s) should measure real agent
token consumption. It specifies the counting approach, baseline definition,
comparison strategy, per-engine capture method, target-project selection
rules, and rerun protocol. **No harness is implemented yet** — that is
delegated to a later subtask ("Run measurements"), which must consume the
definitions below without re-deriving them.

### Token-counting approach

Unlike Part 1's character proxy, Part 2 must use **real token counts as
reported by the engine/model**, because the whole point is to capture actual
agent behavior (tool-call framing, retries, reasoning tokens), which a
character proxy cannot see.

- **Tokenizer / accounting source**: use each engine's own usage accounting
  rather than re-tokenizing text with a third-party tokenizer (e.g. tiktoken).
  Different engines wrap the same underlying model differently (system
  prompts, tool schemas, message framing), so only the engine's own reported
  usage reflects what that engine actually billed/consumed for the run. See
  [Tools/engines](#toolsengines-and-how-to-capture-tokens) for where each
  engine exposes this.
- **Input vs. output tokens**: report both, plus the sum, but headline on the
  **sum (input + output)**, since that is what determines context-window and
  cost impact. Cache-read/cache-write tokens (where the engine reports them
  separately, e.g. Claude prompt caching) must be reported as a third,
  explicitly labeled column rather than folded into "input" — caching can
  make a WITHOUT-MCP run look artificially cheap on a second run if a large
  file is already cached from a prior turn.
- **Per-call vs. end-to-end**: Part 2 is **end-to-end** by definition — total
  tokens for the full agent turn(s) needed to produce a final answer to the
  task, not a single tool call. Where an engine supports it, also record the
  per-tool-call breakdown (useful for diagnosing where savings come from) but
  the headline metric for comparison against Part 1 and across tasks is the
  end-to-end total for the task.
- **Stopping condition**: a "run" ends when the agent emits its final answer
  to the task prompt (the question text from [Queries measured](#queries-measured)
  / [Multi-step tasks](#multi-step-tasks)), not when the process exits. Define
  the task prompt up front, verbatim, and use identical wording for the WITH-
  and WITHOUT-MCP arms of the same task.

### Baseline definition (live run)

The non-ScalaSemantic baseline is the **maximal realistic non-semantic
toolset**, not "no tools": grep/ripgrep, plain file reads, directory listing,
and — where available in the engine — an LSP/Metals-equivalent (go-to-definition,
find-references, hover-for-type) so the comparison isn't artificially
favorable to ScalaSemantic. If the chosen engine has no LSP integration
available in its standard configuration, note that explicitly in the run
report rather than silently dropping the LSP comparator, so it's clear
whether the measured baseline is "grep + reads only" or "grep + reads + LSP".

### Comparison strategy

For each task in the fixed task set (5 single-call queries from
[Queries measured](#queries-measured) + 2-3 multi-step tasks from
[Multi-step tasks](#multi-step-tasks)):

1. Run the task **WITH** ScalaSemantic MCP tools enabled, engine reports
   real token usage for the full turn.
2. Run the same task **WITHOUT** ScalaSemantic MCP tools (baseline toolset
   only, see above), same engine, same model, same task prompt.
3. Repeat each arm **at least 3 times** (more for tasks with high variance)
   to account for agent non-determinism — different runs may grep
   differently, read more or fewer candidate files, or take a different
   reasoning path to the same answer.
4. Per task, report: **tokens per run**, **mean**, **median**, and
   **variance (or standard deviation)** across runs, for both arms, plus the
   **% reduction** computed from the means: `(meanBaseline - meanTool) /
   meanBaseline * 100`.
5. Aggregate across all tasks the same way Part 1 aggregates across queries
   (sum of per-task means, overall % reduction), so the headline number is
   directly comparable in shape to the Part 1 "Overall" row, while remaining
   clearly labeled as a separate measurement (do not merge the two tables).
6. Use identical task ids to Part 1 where a Part 2 task is the live-run
   counterpart of a Part 1 query (e.g. `find-usages-animal`), so a reader can
   line up "proxy savings on fixtures" against "real savings on a live
   project" for the same underlying question.

### Tools/engines and how to capture tokens

Per the parent issue (#72) and #136, account for three engines. Token
observability differs per engine, so the capture method differs too:

| Engine | How token usage is observed | Granularity |
|---|---|---|
| **claude-cli** (Claude Code) | `/cost` command and end-of-session summary report cumulative input/output/cache tokens for the session; for a single isolated task, start a fresh session (`claude` with no prior history) so the reported total is attributable to that one task only | Session-level; per-tool-call breakdown available via transcript inspection if needed |
| **codex** (OpenAI Codex CLI) | CLI prints token usage per turn/session in its run summary; alternatively, run via the underlying API with `usage` field captured from the response object if scripting against the API directly | Turn-level via CLI; call-level via API `usage` |
| **agy** (Gemini CLI, per `agy-gemini-only` constraint — Gemini models only) | Gemini API responses include `usageMetadata` (promptTokenCount, candidatesTokenCount, totalTokenCount); agy should be run with verbose/usage logging enabled if it exposes a flag, otherwise wrap calls through the Gemini API directly to read `usageMetadata` | Call-level via API `usageMetadata` |

General capture rules that apply to all three:

- Always capture usage from the engine/API's own structured output (CLI
  summary, JSON usage field), never by screen-scraping free-text token
  counts that the model might mention in conversation.
- Pin the exact model id/version for each engine in the run report (model
  updates silently change tokenizer and verbosity, breaking comparability
  across reruns).
- Where an engine batches multiple tool calls into one billed turn, that's
  fine — Part 2's headline metric is end-to-end per task, not per call (see
  [Token-counting approach](#token-counting-approach)).
- Record engine CLI version (or API client version) alongside the model id;
  tool-call framing overhead can change between CLI releases independent of
  the underlying model.

### Target project selection criteria (for the live run)

The actual project pick is deferred to the "Run measurements" subtask, but
that subtask must follow these rules:

- **Open-source preferred**: prefer a public repository so results and the
  exact commit/tag used are independently reproducible by any reader; this
  repository (ScalaSemanticMCP) itself is an acceptable fallback if no
  suitable external project is identified, since it dogfoods its own
  SemanticDB output and the questions in the fixed task set already have
  analogues here (the fixture symbols mirror the kinds of code in any
  idiomatic Scala project).
- **Idiomatic Scala**: the project should use traits/case classes,
  inheritance hierarchies, implicits/givens, and multi-file call chains —
  i.e. exercise all five task families ([Queries measured](#queries-measured)),
  not just plain functions. A project with no implicits or no inheritance
  cannot exercise `resolve_implicits`/`trace_implicit_chain` or
  `class_hierarchy` meaningfully.
- **Compiles with SemanticDB**: the project must build with
  `semanticdbEnabled := true` (or equivalent for its build tool) and
  successfully emit `.semanticdb` files, since ScalaSemantic requires that
  output. Verify this with a clean `sbt compile` (or Mill/Maven equivalent)
  before committing to the project, not after the run has started.
- **Size**: prefer a project large enough that `find_usages`-style cross-file
  baselines are non-trivial (i.e. a symbol used in 3+ files), since savings
  on trivial single-file lookups understate the realistic benefit; but not so
  large that a clean compile + SemanticDB indexing becomes itself a
  significant cost or flake risk for the harness. A few thousand to tens of
  thousands of lines of Scala is a reasonable target band.
- **Pinned commit**: record the exact commit SHA used, since the project's
  source will keep changing upstream; the run report must allow a future
  rerun to clone the exact same code.

### Rerun protocol (live run)

To keep the live run reproducible across time and across whoever executes it:

1. **Pin everything**: target project commit SHA, engine CLI/client version,
   model id, and the ScalaSemantic MCP server version/commit under test.
   Record all four in the run report header.
2. **Fresh environment per arm**: each WITH/WITHOUT run starts from a clean
   checkout (or a clean engine session with no prior conversation state) so
   no context or cache leaks between arms or between repeated runs of the
   same arm.
3. **Fixed task prompts**: use the exact task prompt text from
   [Queries measured](#queries-measured) / [Multi-step tasks](#multi-step-tasks)
   verbatim across all runs and both arms; do not let the engine rephrase or
   the operator paraphrase the question between runs.
4. **N ≥ 3 repeats per arm per task**, as specified in
   [Comparison strategy](#comparison-strategy), to get mean/median/variance,
   not a single anecdotal number.
5. **Raw logs retained**: keep the raw engine transcripts/usage JSON for each
   run (not just the aggregated numbers) so a reviewer can audit any
   surprising result without re-running the whole suite.
6. **Report format**: extend the existing JSON schema
   (`docs/research/token-metrics.json`) with a sibling artifact — e.g.
   `docs/research/token-metrics-live.json` — using the same field names
   (`id`, `query`, `tool`, `baseline`, `*Tokens`, `tokenDelta`,
   `savingsPercent`) plus the additional per-run fields this part requires
   (`engine`, `model`, `runs: [...]`, `meanTokens`, `medianTokens`,
   `variance`). Keep it a sibling file rather than overwriting
   `token-metrics.json`, since that file's schema and meaning (Part 1, proxy,
   fixture-only) must stay stable for the existing `TokenMetricsSuite`
   regeneration flow.
7. **Re-run cadence**: re-run the live suite whenever the MCP tool surface
   changes materially (new tool, changed response shape) or whenever a
   target engine ships a major version bump, since either can shift the
   comparison; routine ScalaSemantic patch releases that don't change tool
   output shape do not require a live rerun.

### Limitations (Part 2, anticipated)

- Real agent runs are non-deterministic; even with N ≥ 3 repeats, variance
  may be high for multi-step tasks where the agent's exploration path
  differs run to run. Report variance explicitly rather than only a mean.
- Engines differ in system-prompt size and tool-schema verbosity
  independent of ScalaSemantic; cross-engine comparisons of absolute token
  counts are not meaningful, only within-engine WITH-vs-WITHOUT comparisons
  are. Do not rank engines against each other using these numbers.
- agy is constrained to Gemini models only (see project memory
  `agy-gemini-only`); it cannot be used to measure Claude- or
  OpenAI-backed runs, so its results are a separate data point, not a
  substitute for claude-cli/codex coverage of the same task.
