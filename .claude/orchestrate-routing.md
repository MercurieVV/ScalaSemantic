# Routing knowledge (precached) — which engine + model for which task

Three headless worker engines. Conductor's triage step picks ONE engine + model per task.

## Engines

| Engine | Invoke | Strengths | Weak |
|--------|--------|-----------|------|
| `claude` | Agent tool, `subagent_type: scala-coder` | Scala semantics, multi-file refactors, type reasoning; has scala-semantic MCP | costliest |
| `codex` | `scripts/agent-run.sh codex …` | fast general coding, algorithms, test writing, mechanical edits | weaker on deep Scala type/implicit reasoning |
| `agy` | `scripts/agent-run.sh agy …` | large-context, boilerplate, docs; can also host Sonnet/Opus/GPT-OSS | GUI heritage; no project-native MCP |

## Models per engine

- `claude` (Agent `model:`): `opus` hard reasoning · `sonnet` mid · `haiku` cheap/mechanical
- `codex` (`--model`): default profile; pick reasoning vs mini per difficulty
- `agy` (`--model`, exact strings from `agy models`):
  - `Gemini 3.5 Flash (Low|Medium|High)` — cheap → mid
  - `Gemini 3.1 Pro (Low|High)` — strong reasoning
  - `Claude Sonnet 4.6 (Thinking)`, `Claude Opus 4.6 (Thinking)`, `GPT-OSS 120B (Medium)`

## Routing rules (cheapest engine that clears the bar)

| Task shape | Engine | Model |
|-----------|--------|-------|
| rename / version bump / doc / format / trivial | `codex` or `claude` | mini / `haiku` |
| add tests, small algorithm, isolated bugfix | `codex` | default |
| Scala refactor, type/implicit/hierarchy reasoning, scala-semantic needed | `claude` | `opus` (hard) / `sonnet` (mid) |
| large-context survey, boilerplate gen, docs | `agy` | `Gemini 3.5 Flash (High)` |
| second opinion / parallel bulk of medium tasks | `agy` or `codex` | `Gemini 3.1 Pro (High)` / default |

## Cost discipline
Default to the cheapest tier; escalate model only on retry after a failed task (see skill retry budget). Never send a mechanical task to `opus`.