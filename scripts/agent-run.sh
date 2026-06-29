#!/usr/bin/env bash

# Run one external worker engine (codex|agy) on one task inside a fresh git worktree.
# Does NOT commit, push, or merge — the conductor runs a sanity check on the worktree diff
# first, then ships via scripts/agent-ship.sh. Used by /orchestrate for non-Claude engines.
# (The "claude" engine is driven directly by the conductor via the Agent tool, not this script.)
#
# Usage:
#   scripts/agent-run.sh <engine> <branch> <model> <task-file>
#     engine     codex | agy
#     branch     git-safe branch name (also the worktree dir name)
#     model      engine-specific model string ("" = engine default)
#     task-file  path to a file containing the self-contained task description
#
# On success, prints the worktree path on the last line and leaves it in place (uncommitted
# changes). Exit 0 = engine ran; non-zero = setup/engine error.

set -euo pipefail

engine="${1:?engine required: codex|agy}"
branch="${2:?branch required}"
model="${3-}"
task_file="${4:?task-file required}"

[[ -f "$task_file" ]] || { echo "task-file not found: $task_file" >&2; exit 1; }
task="$(cat "$task_file")"

script_dir="$(cd "$(dirname "$0")" && pwd)"

# Fresh worktree off the latest base (shared with the claude engine via the same script).
wt="$("$script_dir/worktree-new.sh" "$branch")"

prompt="You are one developer working in this checkout: ${wt}
Implement ONLY this task, scoped and matching existing style:

${task}

Touch only files this task requires. Do NOT add scratch files, build artifacts, notes,
or unrelated edits. When done, build/test as the project expects. Do NOT commit or push —
the orchestrator reviews and ships it."

case "$engine" in
  codex)
    ( cd "$wt" && codex exec ${model:+--model "$model"} --dangerously-bypass-approvals-and-sandbox "$prompt" )
    ;;
  agy)
    agy --print --add-dir "$wt" ${model:+--model "$model"} --dangerously-skip-permissions "$prompt"
    ;;
  *)
    echo "unknown engine: $engine" >&2; exit 2 ;;
esac

# Leave the worktree dirty for the conductor's sanity check + ship step.
echo "$wt"