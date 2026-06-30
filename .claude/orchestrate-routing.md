# Routing knowledge (precached) — which engine + model for which task

Three headless worker engines. Conductor's triage step picks ONE engine + model per task.
**Default to codex or agy. Use claude ONLY for hard Scala tasks that need scala-semantic MCP.**

## Engines

| Engine | Invoke | Strengths | Weak |
|--------|--------|-----------|------|
| `codex` | `scripts/agent-run.sh codex …` | fast general coding, algorithms, test writing, mechanical edits | weaker on deep Scala type/implicit reasoning |
| `agy` | `scripts/agent-run.sh agy …` | large-context, boilerplate, docs; hosts Sonnet/Opus/Gemini/GPT-OSS; has scala-semantic MCP | GUI heritage |
| `claude` | Agent tool, `subagent_type: scala-coder` | Scala semantics, multi-file refactors, type/implicit reasoning via scala-semantic MCP | costliest — use last resort |

## Available models per engine

### codex (`--model` flag)
| Model string | Tier | Use when |
|---|---|---|
| `o4-mini` | cheap/fast | trivial, mechanical, docs, rename |
| `o3` | strong reasoning | algorithms, medium Scala edits |
| `o3-pro` | highest reasoning | only on retry escalation |
| _(empty)_ | engine default | fallback if unsure |

Verify available models: `codex --help` or `codex models`.

### agy (`--model` flag, exact strings from `agy models`)
| Model string | Tier | Use when |
|---|---|---|
| `Gemini 3.5 Flash (Low)` | cheapest | trivial edits, formatting |
| `Gemini 3.5 Flash (Medium)` | cheap | small tasks, test additions |
| `Gemini 3.5 Flash (High)` | mid | boilerplate gen, docs, bulk medium |
| `Gemini 3.1 Pro (Low)` | strong | medium Scala, complex algorithms |
| `Gemini 3.1 Pro (High)` | strongest Gemini | large-context, hard analysis |
| `Claude Sonnet 4.6 (Thinking)` | mid-expensive | Scala when agy is preferred over claude agent |
| `Claude Opus 4.6 (Thinking)` | expensive | only on escalation |
| `GPT-OSS 120B (Medium)` | strong OSS | second opinion, bulk medium |

### claude (`model:` in Agent tool)
| Model ID                              | Tier | Use when |
|---------------------------------------|---|---|
| `haiku` (`claude-haiku-4-5-20251001`) | cheapest | triage, sanity-check helpers |
| `sonnet` (`claude-sonnet-5`)          | mid | medium-hard Scala with scala-semantic needed |
| `opus` (`claude-opus-4-8`)            | costliest | hard: deep type/implicit reasoning, complex refactors |

## Routing rules (cheapest engine that clears the bar)

| Task shape | Engine | Model |
|-----------|--------|-------|
| rename / version bump / doc / format / trivial | `codex` | `o4-mini` |
| add tests, small algorithm, isolated bugfix | `codex` | `o3` |
| boilerplate gen, large-context docs, markdown | `agy` | `Gemini 3.5 Flash (High)` |
| bulk medium tasks, second opinion | `agy` | `Gemini 3.1 Pro (High)` |
| medium Scala (scala-semantic useful but not critical) | `agy` | `Claude Sonnet 4.6 (Thinking)` |
| medium Scala (scala-semantic required, agy preferred) | `agy` | `Gemini 3.1 Pro (High)` |
| hard Scala: type/implicit/hierarchy, deep scala-semantic | `claude` | `sonnet` (mid) |
| hardest Scala: complex multi-file refactor, deep reasoning | `claude` | `opus` |

## Conflict resolution routing

When the conductor needs to resolve a merge conflict between two PRs:

| Conflict type | Engine | Model |
|---|---|---|
| Scala source conflict | `claude` | `sonnet` |
| Build file / config conflict | `codex` | `o3` |
| Doc / markdown conflict | `codex` | `o4-mini` |
| Complex multi-file Scala conflict | `claude` | `opus` |

## Module scope

Default module for ambiguous tasks: **`analysis`** (`analysis/src`).
Include other modules only if the task clearly touches them:
- `core/src` — SemanticDB loading/indexing, `SemanticIndex`
- `analysis/src` — Analyzer, result models, upickle types (DEFAULT)
- `mcp/src` — JSON-RPC server, tool dispatch, `Main`

When in doubt, default to `analysis`. Don't over-expand scope.

## Analytic tasks

If a task involves analysis, investigation, research, evaluation, metrics, or profiling — it is **analytic**. Standard routing does not apply. The conductor handles these with a sequential step plan (see SKILL.md).

### Step routing for analytic tasks

Each plan step gets its own engine+model based on what the step does:

| Step shape | Engine | Model |
|---|---|---|
| Locate symbols / read code structure | `claude` (scala-semantic) | `haiku` |
| Aggregate data, count, diff, tabulate | `codex` | `o4-mini` |
| Write analysis report / summarize findings | `agy` | `Gemini 3.5 Flash (High)` |
| Deep reasoning about code patterns | `claude` | `sonnet` |
| Complex multi-file cross-cutting analysis | `claude` | `sonnet` or `opus` |

## Cost discipline

- Default to cheapest tier; escalate model only on retry after failure.
- Never send a mechanical task to `opus` or `Gemini 3.1 Pro (High)`.
- Prefer `codex`/`agy` over `claude`; use `claude` only when scala-semantic MCP is essential.
- On first retry after failure: escalate one model tier within the same engine.
- On second retry: switch to `claude sonnet` if not already there.