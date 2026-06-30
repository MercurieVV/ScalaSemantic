---
name: task-splitting-evaluation
description: Recursive pre-implementation GitHub task splitting and evaluation flow. Use when the user wants Claude agents to evaluate unhandled tasks, skip already-processed tasks, mark easy leaves with detailed executor-ready comments, split broad tasks into GitHub subtasks, and keep recursing until every leaf is well described with a selected LLM/model.
---

# Task Splitting And Evaluation

Use this skill before the implementation-oriented `orchestrate` skill. This flow does not write code, create branches, or merge PRs. It builds a bounded GitHub issue tree so every executable leaf has a clear task description and selected worker engine/model.

## Agents

- `.claude/agents/task-tree-triage.md` - cheap Haiku classifier for one issue. It decides whether to skip, make the issue an executor-ready leaf, or send it to the smart planner for subtask expansion.
- `.claude/agents/task-plan-architect.md` - strongest-model expansion planner. It inspects one broad issue, creates bounded GitHub subtasks, selects a preferred engine/model for each subtask, and comments the parent with the generated task tree.

## Processing Marker

Every processed issue must get exactly one durable marker comment unless it is skipped because an equivalent marker or executor-ready comment already exists:

```markdown
### Agentic task-tree marker

Status: leaf-ready|expanded|skipped
Processed-by: task-splitting-evaluation-v1
Tree depth: <n>
```

This marker is the loop guard. Never process an issue twice in the same run, and never process an issue that already has this marker in its body or comments.

## Outcomes

For each unprocessed issue, choose exactly one outcome:

1. `leaf-ready`
   - Use when the task can be implemented directly by one worker.
   - Add a task comment with a detailed implementation-ready description if the issue does not already have one.
   - Select the preferred executor engine/model from `.claude/orchestrate-routing.md`.
   - The issue becomes a tree leaf.

2. `expanded`
   - Use when the task is broad, vague, risky, or naturally decomposes into dependent steps.
   - Run `.claude/agents/task-plan-architect.md` with the smartest available LLM/model. Do not cost-optimize the planning model for this step.
   - The architect creates one GitHub issue per subtask and links it to the parent as a native GitHub sub-issue with GraphQL `addSubIssue`.
   - Comment the parent with child issue links, dependency order, and per-child executor/model choices.
   - Newly created subtasks enter the queue and are processed by the same rules.

3. `skipped`
   - Use when the task is already described well enough and already has a clear executor/model assignment, or when it was already processed by this flow.
   - Do not create duplicate comments or subtasks.

## Leaf Comment Format

For `leaf-ready`, comment with:

```markdown
### Agentic task-tree marker

Status: leaf-ready
Processed-by: task-splitting-evaluation-v1
Tree depth: <n>
Parent: #<parent or none>
Children: none

Task:
<specific implementation-ready description>

Context:
- <module/file/symbol/dependency note>

Acceptance criteria:
- <observable behavior/result>

Tests:
- <focused test/check using representative Scala cases and SemanticDB dogfooding where relevant>

Preferred executor: <engine> / <model>
Routing rationale: <short reason based on .claude/orchestrate-routing.md>
```

## Recursive Flow

1. Build the initial queue:
   - Default: `gh issue list --state open --limit 200 --json number,title,labels,updatedAt`
   - If the user provides issue numbers, start with only those.
2. Pop one issue at a time.
3. Skip it immediately if it has already been seen in this run or has an `Agentic task-tree marker`.
4. Run `task-tree-triage`.
5. If triage returns `skip`, optionally add a `Status: skipped` marker only when the issue lacks any durable equivalent comment and the skip reason is useful.
6. If triage returns `leaf-ready`, post the leaf comment from triage.
7. If triage returns `needs-expansion`, run `task-plan-architect` with the smartest available LLM/model.
8. Add any child issues created by the architect to the back of the queue.
9. Continue until the queue is empty or a hard limit is reached.
10. After planning, hand selected `leaf-ready` tasks to `.claude/skills/orchestrate/SKILL.md` only when the user wants implementation.

## Subtask Representation

Use this hierarchy strategy:

1. Preferred: native GitHub sub-issues.
   - Create each child with `gh issue create`.
   - Get node IDs with `gh issue view <issue> --json id --jq .id`.
   - Link child to parent with GraphQL `addSubIssue`.
   - Keep the same parent/child links in comments so agents can recover context without relying only on GitHub UI hierarchy.

2. Fallback: markdown parent/child links.
   - If `addSubIssue` fails because the API is unavailable, unauthorized, or unsupported for that issue, do not block the flow.
   - The child issue body must include `Parent: #N`.
   - The parent marker comment must include `Children: #A, #B`.
   - Dependency order must be explicit in comments.

Never create nested checkbox lists as the only subtask representation. They are not durable enough for agent assignment, routing, and per-leaf model selection.

## Recursion Limits

Be smart and conservative:
- Process each issue number at most once per run.
- Maximum tree depth: 2 below the original task.
- Maximum children per expanded issue: 5.
- Maximum new issues per run: 20 unless the user explicitly raises the limit.
- Do not expand a task that can be made into a good leaf with a clear comment.
- If max depth is reached, force `leaf-ready` with the best available executor/model instead of expanding.
- If a proposed split would create tiny mechanical fragments, keep them together as one leaf.

## Expected issue comment chain

Each processed issue should end with one of:

- `Agentic task-tree marker` with `Status: leaf-ready`
- `Agentic task-tree marker` with `Status: expanded` and child issue links
- `Agentic task-tree marker` with `Status: skipped` when a skip needs to be durable

Every tree leaf must be well explained and must name a preferred executor engine/model.

## Cost discipline

- Use `task-tree-triage` for one issue at a time; keep broad scanning cheap.
- Use `task-plan-architect` only for tasks that truly need expansion.
- Prefer compact, high-signal comments that reduce context needed by future agents.
- Do not duplicate full issue bodies, diffs, or source files in comments.
- Prefer comments as the durable state. Do not rely on conversation history for loop prevention.

## Done Criteria

The flow is complete when every selected or discovered issue has been processed once, every expanded parent links to its children, and every leaf has a detailed task comment with a preferred executor engine/model.
