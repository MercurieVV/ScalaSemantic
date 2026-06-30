#!/usr/bin/env bash

# Run one external worker engine (codex|agy) on one task — FULL LIFECYCLE.
# Creates the worktree, runs the engine (which self-sanity-checks and ships via tree2m),
# then reports the result as JSON.
#
# Used by /orchestrate for non-Claude engines. The "claude" engine (scala-coder agent)
# is driven directly by the conductor via the Agent tool and also owns its full lifecycle.
#
# Usage:
#   scripts/agent-run.sh <engine> <branch> <model> <task-file> [touched-areas-json]
#     engine              codex | agy
#     branch              git-safe branch name (also the worktree dir name)
#     model               engine-specific model string ("" = engine default)
#     task-file           path to a file containing the self-contained task description
#     touched-areas-json  JSON array string e.g. '["core/src","analysis/src"]' (optional)
#
# Prints JSON result on stdout:
#   {"status":"success","branch":"...","pr_url":"...","details":"..."}
#   {"status":"merge_conflict","branch":"...","pr_url":"","details":"<conflict info>"}
#   {"status":"error","branch":"...","pr_url":"","details":"<reason>"}
#
# Exit 0 = task completed (check status field). Exit non-zero = setup/engine crash.

set -euo pipefail

engine="${1:?engine required: codex|agy}"
branch="${2:?branch required}"
model="${3-}"
task_file="${4:?task-file required}"
touched_areas="${5:-[]}"

[[ -f "$task_file" ]] || { echo "task-file not found: $task_file" >&2; exit 1; }
task="$(cat "$task_file")"

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"

# ── 1. Create worktree ────────────────────────────────────────────────────────
wt="$("$script_dir/worktree-new.sh" "$branch")"

# Copy gitignored engine configs into the worktree (not tracked, so not present by default).
[[ -f "$repo_root/.codex/config.toml" ]] && { mkdir -p "$wt/.codex"; cp "$repo_root/.codex/config.toml" "$wt/.codex/config.toml"; }
[[ -f "$repo_root/.agents/mcp_config.json" ]] && { mkdir -p "$wt/.agents"; cp "$repo_root/.agents/mcp_config.json" "$wt/.agents/mcp_config.json"; }

# ── 2. Run engine ─────────────────────────────────────────────────────────────
# CRITICAL: engine workdir = worktree ($wt). All file ops, builds, git commands
# must stay inside that path.
prompt="You are one developer. Your working directory for this task is: ${wt}
Work ONLY inside that directory. Do NOT touch other paths.

Implement this task, scoped and matching existing style:

${task}

Steps:
1. cd into ${wt} first. Verify with pwd.
2. Run 'sbt --error compile' from inside ${wt} to generate fresh SemanticDB. This initializes
   scala-semantic analysis tools for the worktree's code. Do this before any code analysis.
3. Implement the task. Touch only files required by the task.
4. No scratch files, build artifacts (target/, .bsp/, *.class), notes, or unrelated edits.
5. Review your own changes with 'git diff' and 'git status --porcelain' before finishing.
   Remove any junk or out-of-scope files.
6. Build and test: run 'sbt --error test' from inside ${wt}. For a full quality check run
   'sbt --error prePush' (clean + fmt + fix + test). Fix any errors before proceeding.
7. Run './tree2m --auto ${branch} <Conventional-Commit message>' from inside ${wt} to commit, push, and merge.
8. Print the PR URL on the last line of your output.

If tree2m fails because the PR is not mergeable (merge conflict), print exactly:
MERGE_CONFLICT: <list conflicting files>
Then stop. Do NOT attempt to resolve the conflict — the orchestrator handles it."

engine_exit=0
case "$engine" in
  codex)
    output=$(cd "$wt" && codex exec ${model:+--model "$model"} --dangerously-bypass-approvals-and-sandbox "$prompt" 2>&1) || engine_exit=$?
    ;;
  agy)
    # agy --add-dir silently falls back when any path component starts with '.'.
    # .worktrees/<branch> has a hidden component, so use a visible symlink instead.
    agy_wt="${repo_root}/worktrees/${branch}"
    ln -sfn "$wt" "$agy_wt"
    output=$(agy --print --add-dir "$agy_wt" ${model:+--model "$model"} --dangerously-skip-permissions "$prompt" 2>&1) || engine_exit=$?
    rm -f "$agy_wt"
    ;;
  *)
    echo "unknown engine: $engine" >&2; exit 2 ;;
esac

# ── 3. Parse result ───────────────────────────────────────────────────────────
if echo "$output" | grep -qi "^MERGE_CONFLICT\|not mergeable\|merge conflict"; then
  conflict_detail=$(echo "$output" | grep -i "MERGE_CONFLICT\|conflict" | head -3 | tr '\n' ' ' | sed 's/"/\\"/g')
  printf '{"status":"merge_conflict","branch":"%s","pr_url":"","details":"%s"}\n' \
    "$branch" "$conflict_detail"
  exit 0
fi

if [[ $engine_exit -ne 0 ]]; then
  error_detail=$(echo "$output" | tail -5 | tr '\n' ' ' | sed 's/"/\\"/g')
  printf '{"status":"error","branch":"%s","pr_url":"","details":"%s"}\n' \
    "$branch" "$error_detail"
  exit 1
fi

pr_url=$(echo "$output" | grep -oE 'https://github\.com/[^/]+/[^/]+/pull/[0-9]+' | tail -1 || true)

# Cleanup worktree on success (tree2m removes the branch; worktree may already be gone)
git -C "$repo_root" worktree remove --force "$wt" 2>/dev/null || true

printf '{"status":"success","branch":"%s","pr_url":"%s","details":"engine=%s model=%s"}\n' \
  "$branch" "${pr_url:-}" "$engine" "${model:-default}"