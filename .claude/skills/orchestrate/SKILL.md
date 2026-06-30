---
name: orchestrate
description: Tree-aware multi-agent GitHub-issue pool. Conductor manages task-tree from task-splitting-evaluation, executing depth-first per branch while parallelizing roots/orphans. Each worker agent owns its subtree lifecycle. Conductor tracks state (pending/started/in-progress/completed/halted) in GitHub + .claude/task-state.json, passes context from parent issue comments, auto-halts dependents. Use after task-splitting-evaluation to execute the resulting task tree.
---

# Orchestrate — tree-aware multi-agent execution

You are the **conductor**. You do NOT implement tasks. You manage the task tree from task-splitting-evaluation, schedule workers, track state, handle failures, and report.

**Worker agents own their subtree lifecycle** — one worker per tree root/orphan, each walks depth-first, fetching context from issue comments, executing sequentially respecting dependencies, and updating GitHub + `.claude/task-state.json`.

Routing knowledge: `.claude/orchestrate-routing.md`. Read it once at start.
**Default to codex/agy. Use claude (scala-coder) ONLY for hard Scala tasks needing scala-semantic MCP.**

## Tree structure (from task-splitting-evaluation)

Each task is a GitHub issue with:
- **task-tree-marker comment**: Status (leaf-ready), Parent, Children, Preferred executor
- **Body**: Task description, Acceptance criteria, Dependencies (e.g., "depends on #129")
- **Dependencies**: explicitly listed in issue body (e.g., "Depends: #129 → read from comment")

Root issues (no parent): #111, #105, #112, #108, #106, #79, #73, #72, #80, #71
- Leaf issues (no children): all others (30+ children from expansion)
- Orphan issues (if any): leaves not linked to any parent via marker

## Invocation modes

### Normal
`/orchestrate` — build task tree, schedule workers, execute depth-first per branch with root/orphan parallelization.

### Dry-run
`/orchestrate --dry-run` (or user says "preview"/"plan only") — build task tree, print execution plan but do NOT launch workers. Show:

| Root | Tree depth | Leaf count | First task | Executor | Status |
|------|-----------|-----------|-----------|----------|--------|
| #112 | 1 | 4 | #128 (research) | agy | pending |
| #111 | 0 | 1 | #111 (value_flow) | claude | pending |

Let user inspect and approve before executing. Only spend tree-building (Haiku) tokens.

### Resume (partial failure)
`/orchestrate --resume` — read `.claude/task-state.json`, continue from last halted/incomplete task, skip done tasks.

### Resume (interruption)
If a task is still `started` or `in-progress` and was not marked `halted`, treat a new launch for
the same branch as an interrupted worker resume. Do not discard the worktree or start over. The
replacement worker must inspect the existing worktree, state files, GitHub comments, git status,
recent commits, and the last recorded live status line, then continue from the earliest step that is
not proven complete.

The conductor chooses the worktree for every child agent. Child agents do not pick or create an
arbitrary worktree by themselves. If `.claude/state.json` has a non-halted `inflight[]` item with a
usable `worktree`, assign the replacement worker to that exact path.

### Merge queue
`./tree2m --auto` is used by all task agents. Requires "Allow auto-merge" enabled in repo branch protection (Settings → General → "Allow auto-merge"). Without it, remove `--auto` flag from task prompts.

## Token discipline

Your context is the costliest in the system. Protect it.
- Delegate mechanical steps to Haiku helpers or scripts.
- State lives in `.claude/task-state.json`, not your message history.
- Never echo full diffs or issue bodies — let helpers summarize.
- Feed worker agents minimum: issue#, task URL, dependency context URLs.
- Don't re-read issues you just dispatched; trust worker agents to fetch context.
- Build task tree once at start; reuse until all done or halted.

## State

`.claude/task-state.json` (source of truth for tree execution):

```json
{
  "meta": {
    "flow": "task-splitting-evaluation → orchestrate",
    "roots": [111, 105, 112, 108, 106, 79, 73, 72, 80, 71],
    "orphans": [],
    "started_at": "2026-06-30T22:15:00Z",
    "last_update": "2026-06-30T22:16:45Z"
  },
  "tree": {
    "111": { "parent": null, "children": [], "depth": 0 },
    "128": { "parent": 112, "children": [], "depth": 1 },
    "130": { "parent": 112, "children": [], "depth": 1, "depends_on": [128] },
    ...
  },
  "tasks": {
    "111": {
      "issue": 111, "title": "feat: value_flow BFS tool",
      "parent": null, "children": [],
      "executor": "claude/sonnet",
      "status": "pending|started|in-progress|completed|halted",
      "depends_on": [],
      "blocked_by": [],
      "result_comment_url": null,
      "error": null,
      "attempts": 0
    },
    "128": {
      "issue": 128, "title": "Research stryker4s setup",
      "parent": 112, "children": [],
      "executor": "agy/Gemini 3.1 Pro",
      "status": "pending|started|in-progress|completed|halted",
      "depends_on": [],
      "blocked_by": [],
      "result_comment_url": "https://github.com/MercurieVV/ScalaSemantic/issues/128#issuecomment-...",
      "error": null,
      "attempts": 1
    },
    "131": {
      "issue": 131, "title": "Mill capability comparison",
      "parent": 108, "children": [],
      "executor": "agy/Gemini 3.1 Pro",
      "status": "pending",
      "depends_on": [129],
      "blocked_by": [129],
      "result_comment_url": null,
      "error": null,
      "attempts": 0
    },
    ...
  }
}
```

Each task status:
- **pending**: in queue, deps not resolved
- **started**: worker agent just launched
- **in-progress**: worker actively executing
- **completed**: done, result in `result_comment_url`
- **halted**: parent halted, or task failed after max attempts

## Loop

### 1. Build task tree
Read `.claude/task-state.json` (if exists) or build from GitHub:
- `gh issue list --state open --json number,title,body` (or user-named set)
- Parse task-tree-marker comments to extract: parent, children, executor, dependencies
- Build `tree{}` mapping (issue → parent/children/depth)
- Build `tasks{}` mapping (issue → status/executor/depends_on)
- Identify roots (parent=null) and orphans (no parent link found)

### 2. Initialize state file
Create `.claude/task-state.json` with meta, tree, tasks. Persist after each change.

### 3. Schedule workers (parallel roots + depth-first per branch)
**Eligible to start**: parent (if exists) is `completed`, all `depends_on` issues are `completed`, task status is `pending`.

**While roots or orphans remain unstarted or in-progress**:
- Spawn one worker agent per eligible root/orphan (up to 3 total workers)
- If a root/orphan already has a non-halted `inflight[]` record and its previous worker is gone or
  interrupted, relaunch the same branch in resume mode and assign the new worker to the recorded
  `worktree`/`agent_workdir` instead of creating a fresh task.
- Before launching any worker, choose its worktree path:
  - Prefer the existing non-halted `inflight[].worktree` when resuming.
  - Otherwise use `<repo-root>/.worktrees/<branch>`.
  - Include the chosen path in the worker prompt and live status update.
- Each worker agent:
  - Walks its subtree depth-first
  - Fetches task from issue comment/body
  - Fetches dependency context from previous issue comment (reads obligatory, must be present)
  - Executes task
  - Posts result to issue comment
  - Updates `task-tree-marker` status in own issue
  - Moves to next eligible child (wait if children blocked by siblings' deps)
- **When a worker finishes a task**: immediately try next task in its subtree (depth-first)
- **When a worker finishes its subtree**: move to next eligible root/orphan (if any)

### 4. Auto-halt dependents
When task status → `halted`:
- Mark all children with `status: halted` and `blocked_by: [parent]`
- Mark all tasks that `depends_on: [halted-issue]` with `status: halted`
- Post comment to each halted issue: "Auto-halted: parent #N halted"
- Update `.claude/task-state.json`

### 5. Finish
When all roots + orphans and all their descendants are `completed` or `halted`: report summary
- Completed tasks + PR URLs
- Halted tasks + reason
- Failed tasks + error

## Launching worker agents

**One worker per root/orphan**, each manages its subtree depth-first.

### Worker agent prompt (all engines)

```
Tree task: #<root-issue>
Subtree: <list of all child issues in this root's tree>
Executor: <engine/model for this root>
Assigned worktree: <absolute path chosen by conductor>

Your job:
1. Detect resume state before doing task work:
   a. Use the assigned worktree from the conductor. Do not choose a different worktree.
   b. If this branch has an existing worktree or `.claude/state.json` `inflight[]` entry with status
      `started`/`in-progress` and no halted/error marker, assume a prior run was interrupted.
   c. Inspect the worktree, `git status --short`, recent commits, open PR/result comments, task-tree
      marker status, `.claude/task-state.json`, and the last `last_stdout_line`/`last_update`.
   d. Decide the last proven completed step and continue from the earliest unsafe/incomplete step.
      Keep useful existing work; remove only clear junk or out-of-scope edits.
2. Walk your subtree depth-first (see below for order)
3. For each task:
   a. Fetch issue #N body & comments from GitHub
   b. Extract task description from issue body or "Task:" section in task-tree-marker comment
   c. Check "Depends on: #M" note in issue body
   d. If task depends on other issue, read the result comment from #M (search for "Status: completed" marker)
   e. Implement the task using dependency context as input
   f. Post result to issue comment (see "Result comment format" below)
   g. Update the issue's task-tree-marker status field to "in-progress" then "completed"
   h. If task has children, move to first unblocked child
   i. If no children, backtrack to sibling or parent's next unblocked sibling
4. On error: post error comment, update status to "halted", STOP (do not process siblings/children)

Subtree depth-first order:
<traversal list, e.g.
#128 (leaf) →
#130 (leaf, depends #128) →
#132 (leaf, depends #128) →
#133 (leaf, depends #130, #132) →
>

**CRITICAL: Dependency context is obligatory.** If a task depends on #M and #M's result comment is missing or incomplete, halt with error: "Missing dependency context from #M"
```

### Result comment format

Post to issue as a new comment:

```markdown
### Task execution result

Status: completed|in-progress|halted
Executor: <engine/model>
Duration: <time>

**Output:**
<deliverable summary, e.g., "Created pr/mcp.go with 450 LoC", or "Wrote docs/findings.md with token comparison table">

**Result:**
<if code: PR link and summary>
<if doc: inline summary or link>
<if analysis: key findings>

**Context for next task:**
<if this task has children, provide summary they need as input, e.g. "Stryker4s setup works on module M with X% mutation score">

**Status field to update in task-tree-marker:** ✓ Completed
```

Also update the task-tree-marker comment's `Status:` field to match (in-progress, completed, halted).

### Engine `claude` (scala-coder)
```
Agent tool:
  subagent_type: "scala-coder"
  model: <model from routing table>
  run_in_background: true
  prompt: <worker agent prompt above>
```

The conductor chooses or reuses the worktree path before launch and passes it to scala-coder.
Scala-coder implements each task depth-first, updates GitHub + state.json, and emits final summary.
It may create the exact assigned path only if the conductor assigned it and it does not exist; it
must not pick another path.

Live status for `.claude/state.json`: when launching a claude/scala-coder worker, include the branch
name and tell it to update its matching `inflight[]` item with:

```bash
scripts/agent-status.sh <branch> \
  --worktree "$PWD" \
  --agent-workdir "$PWD" \
  --last-stdout-line "<latest command or agent status>"
```

The first update must happen immediately after the worker creates or enters its worktree. During
long commands, update `last_stdout_line` with the last meaningful stdout/stderr line so running
flows show `worktree`, `agent_workdir`, `last_stdout_line`, and `last_update`.

If the worktree already exists and the task was not halted, launch the scala-coder worker with an
explicit resume instruction: it must inspect the existing worktree and GitHub/task state, infer the
last completed step, and continue from that point.

### Engine `codex` or `agy`
Write worker prompt to scratchpad file, then:
```bash
scripts/agent-run.sh <engine> <branch> "<model>" <worker-prompt-file> '<subtree-issues-json>' '<assigned-worktree>'
```
Run with `run_in_background: true`. Script handles full lifecycle, posts result comments, and notifies conductor on completion.
It also updates `.claude/state.json` `inflight[]` entries with `worktree`, `agent_workdir`,
`last_stdout_line`, and `last_update` while the worker runs.
If the assigned worktree or branch already exists, `agent-run.sh` reuses it and injects an
interruption-resume context into the worker prompt. If the conductor omits `<assigned-worktree>`,
`agent-run.sh` falls back to any non-halted `inflight[].worktree`, then to
`<repo-root>/.worktrees/<branch>`.

Both engines notify conductor on completion — conductor does NOT poll.

## Analytic & research tasks

Some tasks in the tree are pure research (no code changes), marked with executor `agy/Gemini` or `claude/sonnet` and status `leaf-ready`.

Worker agents handle them the same way as implementation tasks:
- Fetch task description from issue
- Execute (read docs, research, analyze)
- Post findings to issue comment
- Update status to `completed`
- Pass findings to dependent tasks (e.g., #131 reads #129's research findings from its result comment)

No special handling — tree execution applies to all task types.

## Merge conflicts

If a task successfully implements but PR can't merge (conflict with master):
- Worker agent attempts `git rebase origin/master` and posts conflict markers to issue comment
- Conductor detects conflict marker in result comment
- Conductor creates conflict-resolution task (new issue or sub-issue) as a child of the original task
- Mark original task as `completed` (implementation done), new conflict task as `pending`
- Next worker picks up conflict-resolution task when eligible
- Conflict resolver rebases, resolves, and runs tree2m

## Failure & retry

When worker reports task error:
- Update task status to `halted`
- Post error to issue comment with trace
- Auto-halt all dependent tasks (children, tasks depending on this issue)
- Update `.claude/task-state.json`: `status: halted`, `error: "<trace>"`, `attempts: <n>`
- **Retry policy**: on first failure, increment `attempts` and mark `halted` (conductor can retry later via `--resume`)
- Max 2 attempts per task. After 2nd failure, mark permanently `halted` with reason
- Don't loop forever; halt early, let user decide if restart is worth it

Conductor offers `--resume` mode to retry halted tasks (with fresh context, escalated model if desired).

## Guardrails

- **Tree integrity**: never modify the task-tree structure during execution (don't create new issues or unlink parents/children)
- **Max 3 workers** in flight simultaneously (one per root/orphan, up to 3 parallel subtree walks)
- **Dependency obligatory**: if a task lists "depends on #M", #M's result comment MUST exist before starting. Worker halts if missing.
- **Auto-halt propagation**: mark all dependents as halted immediately, don't wait for their turn
- **State file**: `.claude/task-state.json` is source of truth. Read before each decision, write after each state change
- **GitHub as audit log**: result comments and task-tree-marker updates are immutable; state file can be rolled back but GitHub is the record
- **Do NOT bypass pre-push hooks** — correctness owned by them at ship time
- **Trust worker agents**: don't re-verify their diffs in conductor context; conductor owns scheduling/state, workers own execution
