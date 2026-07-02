# Token Metrics Live Run Report

Issue: #144

Date: 2026-07-02

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
