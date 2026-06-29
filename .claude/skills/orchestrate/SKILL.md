---
name: orchestrate
description: Multi-agent GitHub-issue pool. Conductor pulls issues, triages each to the best worker engine+model (claude/codex/agy), runs up to 3 in parallel in isolated git worktrees, and ships each via ./tree2m. Use when the user wants to work through a batch of GitHub issues with the parallel agent pool.
---

# Orchestrate — parallel multi-agent issue pool

You are the **conductor**. You do NOT implement tasks. You triage, schedule, and ship.
Workers (max 3 in flight): `claude` (Agent tool), `codex`, `agy` — all headless, all isolated in their own git worktree per task.

Routing knowledge: `.claude/orchestrate-routing.md`. Read it once at start.

## State

Keep state in a JSON file in the scratchpad: `state.json` with arrays `pool`, `inflight`, `done`, `failed`.
Each task object: `{issue, branch, title, engine, model, difficulty, touched_areas, task, attempts}`.
Keep YOUR OWN context thin — the state file is the source of truth, not your message history.

## Loop

1. **Build pool.** `gh issue list --state open --json number,title,labels` (or the set the user named). One pool entry per issue (number only).
2. **Triage (sequential, cheap).** For each pool issue, spawn the `triage` subagent (Haiku) with the issue number. Parse its JSON → fill the task object. Do this serially; it's cheap and keeps routing decisions clean.
3. **Schedule.** While `pool` not empty:
   - While `len(inflight) < 3` AND a pool task exists whose `touched_areas` does NOT overlap any inflight task's `touched_areas`: launch it (below), move task pool→inflight.
   - If no disjoint task is available but slots are free, wait for an inflight task to finish before launching an overlapping one (avoids guaranteed conflicts).
4. **On worker completion** (you are re-invoked when a background task finishes):
   - Worker already ran `./tree2m` itself. tree2m output tells you pass/fail (PR merged vs hook/CI error).
   - Success → move task inflight→done.
   - Failure → retry policy below.
5. Repeat until `pool` and `inflight` are both empty. Report `done`/`failed` summary with PR links.

## Launching a worker

- **engine `claude`**: `Agent` tool, `subagent_type: "scala-coder"`, `model: <model>`, `run_in_background: true`, `isolation: "worktree"`. Prompt = the task object's `task` plus its `branch` and the instruction to ship via `./tree2m <branch> "<msg>"`.
- **engine `codex` / `agy`**: write the `task` text to a scratchpad file, then `Bash` `run_in_background: true`:
  `scripts/agent-run.sh <engine> <branch> "<model>" <task-file>`
  The script makes the worktree, runs the engine, and ships via tree2m. Exit 0 = merged.

Both paths notify you on completion — do not poll in a sleep loop.

## Retry policy (cost-aware)

- `attempts` starts 0. On failure, if `attempts < 2`: **escalate** — bump to the next stronger model (and/or switch engine `codex/agy → claude`), set the task back to `pool`, `attempts++`.
- On 2nd failure → `failed[]` with the captured error. Do not loop forever.
- Never escalate a *passing* task; never start a mechanical task on `opus`.

## Conflicts
Overlapping `touched_areas` are serialized, not blocked. Two merged PRs may still conflict at `master` (independent developers). That's expected — if a later tree2m fails because its branch went stale, treat it as a normal failure → retry (re-worktrees off fresh `origin/master`).

## Guardrails
- The gate is the precommit/prepush hooks invoked by tree2m. Do not bypass them.
- If `gh`, `codex`, or `agy` is missing, report it and run with the engines that exist.
- Keep ≤3 in flight. More worktrees = more conflicts and cost with little speedup.