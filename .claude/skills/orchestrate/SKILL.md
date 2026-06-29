---
name: orchestrate
description: Multi-agent GitHub-issue pool. Conductor pulls issues, triages each to the best worker engine+model (claude/codex/agy), runs up to 3 in parallel in isolated git worktrees, sanity-checks each diff, and ships via ./tree2m. Use when the user wants to work through a batch of GitHub issues with the parallel agent pool.
---

# Orchestrate — parallel multi-agent issue pool

You are the **conductor**. You do NOT implement tasks. You triage, schedule, sanity-check, and ship.
Workers (max 3 in flight): `claude` (Agent tool), `codex`, `agy` — all headless, each isolated in its own git worktree per task. Workers implement and STOP; the conductor reviews and ships.

Routing knowledge: `.claude/orchestrate-routing.md`. Read it once at start.

## Token discipline (applies to the whole flow)

 Tokens are a budget — spend them where they change the outcome. You (the conductor) run on an expensive model; your context is the costliest tokens in the system. Protect it.
- **Delegate down whenever it's safe.** Any sub-step that is mechanical or low-judgment — parsing an issue, summarizing a diff, generating a branch slug or commit message, wrangling JSON, classifying difficulty — should be handed to a cheap helper agent (Haiku), not done inline in your context. Spend your own reasoning only on scheduling, routing decisions, and handling failures. If a cheaper model would do it without harming the result, use the cheaper model.
- Stay thin: state lives in `state.json`, not your message history. Don't echo full diffs/issue bodies back into your context — let a Haiku helper read them and return only the verdict/summary you need.
- Cheapest capable model per step: triage = Haiku, sanity = Haiku, workers per routing table (escalate only on retry).
- Feed sub-agents the minimum: a branch, a path, the self-contained `task`, expected `touched_areas` — not the whole repo or prior conversation.
- Don't re-read files you just wrote; don't re-triage a task you already routed.

## Reusable scripts — one call per phase

Every recurring git chain in this flow is a script. Call the script; never re-issue the steps one-by-one as separate Bash calls (each round-trip costs tokens). If you find yourself repeating a new multi-step chain across tasks, add a script for it under `scripts/` (mirror `tree2m`: `rtk`, quiet output) instead of paying the round-trips again.

| Phase | Script (one call) |
|-------|-------------------|
| new isolated workspace | `scripts/worktree-new.sh <branch>` → prints worktree path |
| run codex/agy worker | `scripts/agent-run.sh <engine> <branch> "<model>" <task-file>` |
| sanity signal for the gate | `scripts/worktree-diff.sh <wt>` |
| ship after sanity pass | `scripts/agent-ship.sh <branch> "<msg>"` (tree2m + cleanup) |
| scoped commit+push (no merge) | `scripts/commit-push.sh <branch> "<msg>" <paths…>` |

## State

`state.json` (scratchpad) with arrays `pool`, `inflight`, `done`, `failed`.
Each task: `{issue, branch, title, engine, model, difficulty, touched_areas, task, worktree, attempts}`.

## Loop

1. **Build pool.** `gh issue list --state open --json number,title,labels` (or the set the user named). One entry per issue (number only).
2. **Triage (sequential, cheap).** Spawn the `triage` subagent (Haiku) per issue → parse JSON → fill the task object. Serial; cheap; keeps routing clean.
3. **Schedule.** While `pool` not empty:
   - While `len(inflight) < 3` AND a pool task whose `touched_areas` does NOT overlap any inflight task exists: launch it, move pool→inflight.
   - If only overlapping tasks remain, wait for an inflight one to finish first (avoids guaranteed conflicts).
4. **On worker completion** (you are re-invoked when a background task finishes):
   a. **Sanity check** — spawn `sanity-check` (Haiku) with the task's `worktree` + `touched_areas`. Cheap pre-merge gate for junk/scope-creep (NOT correctness — hooks own that).
      - `fail` → don't ship. Treat as a failed attempt; retry policy below (feed the offending paths back so the next attempt avoids them).
   b. **Ship** — on `pass`, run `scripts/agent-ship.sh <branch> "<msg>"`. It runs `./tree2m` (commit→push→hooks gate→CI→squash-merge) then removes the worktree.
      - success → inflight→done. failure (hooks/CI) → retry policy.
5. Repeat until `pool` and `inflight` empty. Report `done`/`failed` with PR links.

## Launching a worker

All engines work in a dedicated worktree off fresh `origin/master` and leave it **uncommitted** for the sanity gate.

- **engine `claude`**: create the worktree with `scripts/worktree-new.sh <branch>` (prints the path; record in `state.json` `worktree`).
  Then `Agent` tool, `subagent_type: "scala-coder"`, `model: <model>`, `run_in_background: true`. Prompt = the `task`, the worktree path, and "work only in that path; do not commit/push/tree2m; stop and report changed files."
- **engine `codex` / `agy`**: write `task` to a scratchpad file, then `Bash` `run_in_background: true`:
  `scripts/agent-run.sh <engine> <branch> "<model>" <task-file>`
  The script makes the worktree, runs the engine, leaves it dirty, and prints the worktree path (record it).

Both notify you on completion — do not poll in a sleep loop.

## Retry policy (cost-aware)

- `attempts` starts 0. On failure (sanity OR hooks/CI), if `attempts < 2`: **escalate** — stronger model (and/or `codex/agy → claude`), set task back to `pool`, `attempts++`. For sanity failures, include the offending paths in the retry task so the worker doesn't repeat them. Remove the stale worktree before retrying.
- 2nd failure → `failed[]` with the captured reason. Don't loop forever.
- Never escalate a *passing* task; never start a mechanical task on `opus`.

## Conflicts
Overlapping `touched_areas` are serialized, not blocked. Two merged PRs may still conflict at `master` (independent developers) — expected. If a later ship fails because its branch went stale, treat as normal failure → retry off fresh `origin/master`.

## Guardrails
- Gates: (1) cheap `sanity-check` for junk/scope before merge, (2) precommit/prepush hooks via tree2m for correctness. Do not bypass either.
- If `gh`, `codex`, or `agy` is missing, report it and run with the engines that exist.
- Keep ≤3 in flight. More worktrees = more conflicts and cost with little speedup.