---
name: gh-task
description: Run a GitHub issue end-to-end in this repository using the committed isolated-worktree workflow. Use when the user invokes $gh-task, asks to run a GitHub task, or provides a GitHub issue number, #issue, or issue URL that should be implemented, verified, PR'd, merged, commented, closed, and cleaned up.
---

# GitHub Task

## Overview

Run one GitHub issue through the repository workflow in `docs/commands/gh-task.md`.
Treat that file as the canonical procedure and load it before doing task work.

## Input

Accept a GitHub issue URL, `#123`, or `123`.
Resolve the numeric issue id and use that exact id as the worktree and branch name.

## Workflow

1.Follow `docs/commands/gh-task.md` exactly unless the current user gives a newer conflicting instruction.
2.Keep all implementation work inside `.worktrees/<issue-id>` after the worktree is created.

## Guardrails

- Do not delete or revert unrelated user changes.
- Stop with the exact blocker and current branch/worktree state if credentials, external services, or ambiguous requirements prevent completion.
- Use ScalaSemantic MCP tools for Scala source analysis after compiling, when available.
