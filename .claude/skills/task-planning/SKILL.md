---
name: task-planning
description: Pre-implementation GitHub task planning flow. Use when the user wants Claude agents to fetch open tasks, prioritize them, identify dependencies, create per-task implementation plans, choose the preferred LLM/model for each step, and write the results back into task comments.
---

# Task Planning

Use this skill before the implementation-oriented `orchestrate` skill. This flow does not write code, create branches, or merge PRs. It prepares GitHub issues so later agents can execute them with less context rediscovery.

## Agents

- `.claude/agents/task-prioritizer.md` — cheap Haiku pass over all open tasks. Fetches issues, assigns priority/order, identifies explicit and inferred dependencies, and comments those notes on each issue.
- `.claude/agents/task-plan-architect.md` — strongest-model per-task planner. Reads one prioritized issue, inspects needed project context, writes an implementation plan, selects the preferred engine/model for each step, and comments the plan on the issue.

## Flow

1. Run `task-prioritizer` first.
   - Input: repo root and optionally a subset of issue numbers.
   - Output: issue comments with `Agentic priority note`.
   - Purpose: establish execution order, dependencies, blockers, and project-value rationale.

2. Run `task-plan-architect` for each issue that should be planned.
   - Prefer `now` and `next` tasks first.
   - Skip `blocked` tasks unless the user explicitly wants plans for blocked work.
   - Input: one issue number at a time.
   - Output: issue comment with `Agentic implementation plan`.

3. After planning, hand selected tasks to `.claude/skills/orchestrate/SKILL.md` only when the user wants implementation.

## Expected issue comment chain

Each planned issue should have:

1. `Agentic priority note`
2. `Agentic implementation plan`

The priority note explains why the task matters and how it relates to other work. The implementation plan explains how to do it and which engine/model should handle each step.

## Cost discipline

- Use the prioritizer for broad open-task scanning; keep it cheap.
- Use the architect only after prioritization, because it spends the strongest model on task-specific planning.
- Prefer compact, high-signal comments that reduce context needed by future agents.
- Do not duplicate full issue bodies, diffs, or source files in comments.

## Done Criteria

The flow is complete when every selected issue has a priority/dependency comment and every unblocked selected issue has an implementation-plan comment with per-step model choices.
