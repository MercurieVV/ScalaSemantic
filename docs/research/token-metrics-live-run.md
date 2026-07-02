# Token Metrics Live Run Attempt

Issue: #144

Date: 2026-07-02

## Target Project

The live run used this repository as the target project:

- Repository: `https://github.com/MercurieVV/ScalaSemantic.git`
- Commit: `00a10cf4e267ce7c6eb5783856e797c5ed6edb7b`
- Rationale: this is an open-source Scala project that already emits SemanticDB,
  dogfoods the ScalaSemantic MCP server, and contains the fixed fixture symbols
  used by the Part 1 token study.

The worktree was compiled before measurement setup:

```bash
rtk sbt --error compile
```

## Engine Availability

| Engine | CLI version | Token accounting observed | Result |
| --- | --- | --- | --- |
| codex | `codex-cli 0.142.5` | `codex exec --json` emits `turn.completed.usage` with input, cached input, output, and reasoning output tokens. | Token accounting works, but ScalaSemantic MCP tool calls were cancelled by the Codex tool host in this nested noninteractive run. |
| claude-cli | `2.1.197 (Claude Code)` | `--output-format json` includes a `usage` object. | The local CLI was not logged in, so no model run could be measured. |
| agy | `1.0.15` | No structured token accounting was found in stdout or the selected log file for print mode. | The CLI can answer, but this run could not extract per-task token counts from agy. |

## Attempted Task

Task prompt family:

> Find all definitions and references of the `Animal` trait in this repository's fixture sources.

This corresponds to the existing `find-usages-animal` task from the Part 1
study and should compare:

- WITH MCP: `find_symbol` / `find_usages` through the ScalaSemantic MCP server.
- WITHOUT MCP: repository search and file reads only.

## Observed Runs

Codex was the only engine that exposed per-turn token accounting in this
environment. Three smoke runs were made:

| Run | Arm intended | Outcome | Input tokens | Cached input tokens | Output tokens | Reasoning output tokens |
| ---: | --- | --- | ---: | ---: | ---: | ---: |
| 1 | without-mcp | Completed with repository search fallback. | 106849 | 82688 | 1612 | 347 |
| 2 | with-mcp | ScalaSemantic MCP server was visible, but `find_symbol` tool calls were cancelled; Codex fell back to grep/file reads. | 109553 | 75008 | 1292 | 553 |
| 3 | with-mcp probe | ScalaSemantic MCP server was visible, but repeated `find_symbol` calls were cancelled; Codex fell back to grep/file reads. | 184583 | 142720 | 1188 | 298 |

Because the intended WITH-MCP arm did not complete via ScalaSemantic tools,
these rows are not a valid WITH-vs-WITHOUT comparison and were not converted
into `docs/research/token-metrics-live.json`.

## ScalaSemantic MCP Setup Attempt

A local development launcher was generated with:

```bash
rtk sbt --batch mcpLauncher
```

Codex accepted an explicit MCP override and listed `scala-semantic` as an
enabled server:

```bash
rtk codex mcp list \
  -c 'mcp_servers.scala-semantic.command="/.../.worktrees/144/target/out/jvm/scala-3.8.4/scalasemantic-mcp/scalasemantic-mcp"' \
  -c 'mcp_servers.scala-semantic.args=["/.../.worktrees/144"]'
```

However, noninteractive `codex exec` runs reported each ScalaSemantic MCP tool
call as:

```text
user cancelled MCP tool call
```

Adding per-tool `approval_mode = "auto"` overrides for `find_symbol` and
`find_usages` did not change the result.

## Conclusion

This run selected and prepared a reproducible open-source target project and
verified live token observability for the available engines. It did not produce
a scientifically valid aggregate table because no engine produced successful
per-task token records for both arms:

- Codex exposes usable token counts, but ScalaSemantic MCP calls were cancelled
  in the nested noninteractive run.
- Claude Code exposes a usage object, but the local CLI was not authenticated.
- agy completed a print-mode response, but no structured token usage was exposed
  by stdout or the selected log file.

The next live run should execute from an interactive or pre-approved Codex
environment where ScalaSemantic MCP tool calls are allowed, or use the
underlying model APIs directly so both tool availability and token usage are
controlled in one harness.
