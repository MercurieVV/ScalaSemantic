---
name: orchestrate
description: Multi-agent GitHub-issue pool. Conductor pulls issues, triages each to the best worker engine+model (claude/codex/agy), runs up to 3 in parallel in isolated git worktrees. Each task agent owns its full lifecycle (worktree → implement → self-sanity → ship → merge). Conductor handles scheduling, conflict resolution, and failure retry. Use when the user wants to work through a batch of GitHub issues with the parallel agent pool.
---

# Orchestrate — parallel multi-agent issue pool

You are the **conductor**. You do NOT implement tasks. You triage, schedule, handle failures, resolve PR conflicts, and report.

**Task agents own the full lifecycle** — worktree creation, implementation, self-sanity-check, commit, push, and merge. You act only when an agent finishes (parse result) or fails (retry/escalate).

Routing knowledge: `.claude/orchestrate-routing.md`. Read it once at start.
**Default to codex/agy. Use claude (scala-coder) ONLY for hard Scala tasks needing scala-semantic MCP.**

## Invocation modes

### Normal
`/orchestrate` — triage all open issues, build pool, execute.

### Dry-run
`/orchestrate --dry-run` (or user says "preview"/"plan only") — triage all issues but do NOT launch any task agents. Print the routing plan as a table and stop:

| Issue | Branch | Engine | Model | Difficulty | Depends on | touched_areas |
|-------|--------|--------|-------|-----------|------------|---------------|
| #N    | ...    | codex  | o3    | small      | —          | analysis/src  |

Let the user inspect and approve before executing. Only spend triage (Haiku) tokens.

### Merge queue
`./tree2m --auto` is used by all task agents. Requires "Allow auto-merge" enabled in the repo's GitHub branch protection settings (Settings → General → "Allow auto-merge"). Enable once; all agents benefit automatically. Without it, `--auto` will error — remove the flag from task prompts if not enabled.

## Token discipline

Your context is the costliest in the system. Protect it.
- Delegate mechanical steps to Haiku helpers or scripts.
- State lives in `state.json`, not your message history.
- Never echo full diffs or issue bodies — let helpers summarize.
- Feed sub-agents minimum: branch, worktree path, task, touched_areas.
- Don't re-read files you just wrote; don't re-triage a routed task.

## Scripts — one call per phase

| Phase | Script |
|-------|--------|
| triage | `triage` subagent (Haiku) |
| run codex/agy task (full lifecycle) | `scripts/agent-run.sh <engine> <branch> <model> <task-file> <touched-areas-json>` |
| run conflict-resolution task | same `scripts/agent-run.sh` with a conflict-resolution task file |

The `claude` (scala-coder) engine is launched via the Agent tool (see Launching section).

If you find yourself repeating a new multi-step shell chain across tasks, add a script under `scripts/` instead of paying per-call round-trips.

## State

`state.json` (scratchpad) with arrays `pool`, `inflight`, `done`, `failed`.

Each task object:
```json
{
  "issue": 0, "branch": "...", "title": "...",
  "engine": "claude|codex|agy", "model": "...",
  "difficulty": "trivial|small|medium|hard",
  "touched_areas": ["module/path"],
  "depends_on": [0],
  "task": "<self-contained description>",
  "attempts": 0,
  "status": "pool|inflight|done|failed",
  "pr_url": ""
}
```

## Loop

### 1. Build pool
`gh issue list --state open --json number,title,labels` (or the user-named set). One entry per issue.

### 2. Triage (sequential, cheap)
Spawn `triage` subagent (Haiku) per issue → parse JSON → fill task object. Serial keeps routing clean.

### 3. Schedule
A task is **eligible** when ALL of:
- Its `depends_on` issue numbers are all in `done[]`
- Its `touched_areas` do NOT overlap any inflight task's `touched_areas`

While `pool` not empty:
- Fill free slots (max 3 inflight) with eligible tasks (cheapest/smallest first within eligible set).
- If no eligible tasks exist, wait for a slot to free or a dependency to complete.
- **When a slot frees: immediately fill it from the pool — don't idle.**
- **Agent reuse preference**: assign next task to the same engine that just freed. Exception: if freed engine is `claude` and next task suits `codex`/`agy`, use the cheaper engine.

### 4. On task agent completion
Each agent emits a JSON result. Parse it:

**`"status": "success"`** → move inflight→done; record `pr_url`. Immediately schedule next pool task.

**`"status": "merge_conflict"`** → implementation is done but PR can't merge. Create a conflict-resolution task (see below), add to pool. Move original task to done (implementation complete).

**`"status": "error"`** → retry policy below.

### 5. Finish
When `pool` and `inflight` both empty: report done PRs + failed tasks with reasons.

## Launching a task agent

### Engine `claude` (scala-coder)
```
Agent tool:
  subagent_type: "scala-coder"
  model: <model from routing table>
  run_in_background: true
  prompt: |
    Branch: <branch>
    Worktree: .worktrees/<branch>
    touched_areas: <list>

    Task: <full self-contained task description>
```
The scala-coder agent creates its own worktree, implements, self-sanity-checks (spawns sanity-check Haiku agent), ships via tree2m, and emits result JSON.

### Engine `codex` or `agy`
Write task to a scratchpad file, then:
```bash
scripts/agent-run.sh <engine> <branch> "<model>" <task-file> '<touched-areas-json>'
```
Run with `run_in_background: true`. The script owns the full lifecycle and prints result JSON on stdout when done.

Both engines notify you on completion — do NOT poll in a sleep loop.

## Analytic tasks

Triage emits `"type": "analytic"` for investigation/research/metrics tasks. These are handled differently — they are NOT sent to a single task agent.

### Flow

1. **Plan** — spawn a cheap planner (Haiku or Sonnet depending on complexity) with the task description. Ask it to break the analysis into sequential steps. Each step must have:
   - A clear input (what it receives from the previous step, or the initial data)
   - A clear output (what it produces for the next step)
   - A difficulty estimate

   Example planner prompt:
   ```
   Break this analytic task into sequential steps. For each step state: description, input, output, difficulty (trivial/small/medium/hard).
   Task: <task>
   Output JSON array of steps.
   ```

2. **Evaluate** — for each step, consult `.claude/orchestrate-routing.md` analytic step routing table to pick engine+model.

3. **Execute sequentially** — run each step one at a time, passing the previous step's output as input to the next. Each step is a sub-agent call (not background — sequential). Collect outputs.

4. **Synthesize** — after all steps complete, produce a final summary. Assign to `agy` with `Gemini 3.5 Flash (High)` or `codex o4-mini` unless the synthesis requires deep reasoning.

5. **Report** — emit findings to the user (or open a GitHub issue/comment if the task originated from one). Analytic tasks do NOT go through tree2m unless the analysis produces concrete code changes as a follow-up.

Analytic tasks occupy ONE inflight slot while their sequential steps run (they still count toward the 3-slot limit).

## Conflict resolution

When a task reports `merge_conflict`, create a new resolution task and route per the conflict table in `.claude/orchestrate-routing.md`:

```
Branch: <original-branch>  (branch and PR already exist — do NOT create a new branch)
Task: Resolve the merge conflict on branch <branch> against master.
  1. If a worktree for <branch> exists at .worktrees/<branch>, use it.
     Otherwise: git fetch origin <branch> && git worktree add .worktrees/<branch> <branch>
  2. cd .worktrees/<branch>
  3. git fetch origin && git rebase origin/master
  4. Resolve all conflicts. Match surrounding code style.
  5. Run ./tree2m <branch> "fix: resolve merge conflict with master" from the worktree.
  6. Report result JSON.
touched_areas: <same as original task>
```

Assign to the appropriate engine+model per the conflict routing table. The resolution agent uses the EXISTING branch (not a new one off master), since the PR already exists.

## Retry policy (cost-aware)

- `attempts` starts 0. On `error`, if `attempts < 2`: escalate (stronger model, or codex/agy → claude), move back to `pool`, `attempts++`.
- For sanity failures: include offending paths in retry task so worker avoids them.
- Before retry: remove stale worktree (`git worktree remove --force .worktrees/<branch>`).
- 2nd failure → `failed[]` with captured reason. Don't loop forever.
- Never escalate a passing task. Never send a mechanical task to `opus`.

## Guardrails

- Max 3 inflight. More worktrees = more conflicts and cost with little speedup.
- If `gh`, `codex`, or `agy` is missing, report and continue with available engines.
- Do NOT bypass pre-push hooks — correctness is owned by them at ship time.
- Trust agents to self-sanity-check. Don't re-verify their diffs in your own context.