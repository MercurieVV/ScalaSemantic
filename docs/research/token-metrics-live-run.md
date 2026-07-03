# Token Metrics Live Run Report

Issue: #144

Date: 2026-07-02 (codex run); 2026-07-03 (claude-cli rerun, see
[claude-cli section](#claude-cli-rerun-2026-07-03) below)

## Target Project

The live run used this repository as the target project:

- Repository: `https://github.com/MercurieVV/ScalaSemantic.git`
- Commit: `99b17c3981a1fb50ed20587ab92afcfd7fc2d66a`

## Engine and Model Details

- **Engine**: Codex
- **Version**: `codex-cli 0.142.5`
- **Model**: `o3`

## Measured Task

> Find all definitions and references of the `Animal` trait in this repository's fixture sources.

## Results Summary

- **WITHOUT MCP (Baseline)**: Average total tokens = 156580.3 (Input: 154179.3, Output: 2401.0)
- **WITH MCP (ScalaSemantic)**: Average total tokens = 150239.3 (Input: 148626.7, Output: 1612.7)
- **Token Savings**: 6341.0 tokens (4.0% reduction)

## Detailed Runs

| Run | Arm | Input Tokens | Cache Tokens | Output Tokens | Total Tokens |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | with-mcp | 114621 | 84608 | 1083 | 115704 |
| 2 | with-mcp | 201603 | 147584 | 2251 | 203854 |
| 3 | with-mcp | 129656 | 100352 | 1504 | 131160 |
| 1 | without-mcp | 179500 | 135424 | 2641 | 182141 |
| 2 | without-mcp | 125781 | 97920 | 2093 | 127874 |
| 3 | without-mcp | 157257 | 128384 | 2469 | 159726 |

## Conclusion

Using the ScalaSemantic MCP server allows Codex to perform precise definitions and usages analysis using high-signal SemanticDB facts rather than doing full-text grep and reading raw files. This leads to a **4.0%** reduction in total tokens consumed.

## claude-cli rerun (2026-07-03)

The first live run produced no claude-cli data (the CLI was not logged in).
After fixing authentication, the same task was rerun on claude-cli:

- **Engine**: Claude Code (claude-cli), version `2.1.199`
- **Model**: `claude-sonnet-5`
- **Commit**: `cf9d6bbd8e310f2794460fa784188072095072a7`
- **ScalaSemantic server version**: `0.3.10` (published launcher)
- Same task prompt, 3 repetitions per arm, fresh session per run.
- Token accounting: `modelUsage` from `--output-format json` (sums all model
  calls including subagents); `inputTokens` = input + cacheCreation +
  cacheRead so it is total-context-consumed, matching codex semantics;
  `cacheTokens` = the cacheRead subset.
- Arm validity checked in session transcripts: with-mcp runs made 6-8
  `mcp__scala-semantic__*` calls and zero Grep/Read; without-mcp runs used
  Grep/Bash/Explore-agent only and made zero ScalaSemantic calls.

### Results Summary (claude-cli)

- **WITHOUT MCP (Baseline)**: Average total tokens = 266332.7
- **WITH MCP (ScalaSemantic)**: Average total tokens = 154085.0
- **Token Savings**: 112247.7 tokens (**42.1%** reduction)

### Detailed Runs (claude-cli)

| Run | Arm | Input Tokens | Cache Tokens | Output Tokens | Total Tokens |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | with-mcp | 177329 | 140374 | 729 | 178058 |
| 2 | with-mcp | 141374 | 119938 | 887 | 142261 |
| 3 | with-mcp | 141473 | 121642 | 463 | 141936 |
| 1 | without-mcp | 239103 | 225652 | 1474 | 240577 |
| 2 | without-mcp | 213650 | 187263 | 1437 | 215087 |
| 3 | without-mcp | 338814 | 281814 | 4520 | 343334 |

Unlike the codex 4.0% result, this delta is larger than the run-to-run
spread: the with-mcp arm's standard deviation is ~17k tokens and the
without-mcp arm's ~55k, against a 112k mean delta (~2x the noisier arm's
standard deviation). The without-mcp run 3 outlier (343k) spawned an Explore
subagent; excluding it, the reduction is still ~32%.
