#!/usr/bin/env bash

# Run one external worker engine (codex|agy) on one task — FULL LIFECYCLE.
# Creates or resumes the worktree, runs the engine (which self-sanity-checks and ships via tree2m),
# then reports the result as JSON.
#
# Used by /orchestrate for non-Claude engines. The "claude" engine (scala-coder agent)
# is driven directly by the conductor via the Agent tool and also owns its full lifecycle.
#
# Usage:
#   scripts/agent-run.sh <engine> <branch> <model> <task-file> [touched-areas-json] [assigned-worktree]
#     engine              codex | agy
#     branch              git-safe branch name (also the worktree dir name)
#     model               engine-specific model string ("" = engine default)
#     task-file           path to a file containing the self-contained task description
#     touched-areas-json  JSON array string e.g. '["core/src","analysis/src"]' (optional)
#     assigned-worktree   worktree path chosen by the conductor (optional)
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
assigned_worktree="${6:-}"

[[ -f "$task_file" ]] || { echo "task-file not found: $task_file" >&2; exit 1; }
task="$(cat "$task_file")"

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
state_root="$repo_root"
case "$repo_root" in
  */.worktrees/*)
    state_root="${repo_root%%/.worktrees/*}"
    ;;
esac

state_worktree="$(
  python3 - "$state_root/.claude/state.json" "$branch" <<'PY' 2>/dev/null || true
import json
import sys

state_path, branch = sys.argv[1], sys.argv[2]
try:
    with open(state_path, "r", encoding="utf-8") as fh:
        state = json.load(fh)
except (OSError, json.JSONDecodeError):
    sys.exit(0)

for task in state.get("inflight", []):
    if task.get("branch") == branch and task.get("status") != "halted":
        worktree = task.get("worktree") or task.get("agent_workdir")
        if worktree:
            print(worktree)
        break
PY
)"

# ── 1. Create or resume worktree ──────────────────────────────────────────────
wt="${assigned_worktree:-${state_worktree:-${repo_root}/.worktrees/${branch}}}"
resume_mode=0
if [[ -d "$wt/.git" || -f "$wt/.git" ]]; then
  resume_mode=1
elif git -C "$repo_root" show-ref --verify --quiet "refs/heads/${branch}"; then
  git -C "$repo_root" worktree add "$wt" "$branch" >&2
  resume_mode=1
elif git -C "$repo_root" show-ref --verify --quiet "refs/remotes/origin/${branch}"; then
  git -C "$repo_root" worktree add -b "$branch" "$wt" "origin/${branch}" >&2
  resume_mode=1
else
  wt="$("$script_dir/worktree-new.sh" "$branch")"
fi
"$script_dir/agent-status.sh" "$branch" \
  --worktree "$wt" \
  --agent-workdir "$wt" \
  --last-stdout-line "$([[ "$resume_mode" -eq 1 ]] && echo "resuming existing worktree: $wt" || echo "worktree created: $wt")" || true

# Copy gitignored engine configs into the worktree (not tracked, so not present by default).
[[ -f "$repo_root/.codex/config.toml" ]] && { mkdir -p "$wt/.codex"; cp "$repo_root/.codex/config.toml" "$wt/.codex/config.toml"; }
[[ -f "$repo_root/.agents/mcp_config.json" ]] && { mkdir -p "$wt/.agents"; cp "$repo_root/.agents/mcp_config.json" "$wt/.agents/mcp_config.json"; }

# ── 2. Run engine ─────────────────────────────────────────────────────────────
# CRITICAL: engine workdir = worktree ($wt). All file ops, builds, git commands
# must stay inside that path.
resume_context=""
if [[ "$resume_mode" -eq 1 ]]; then
  status_snapshot="$(git -C "$wt" status --short 2>/dev/null || true)"
  commit_snapshot="$(git -C "$wt" log --oneline --decorate -5 2>/dev/null || true)"
  resume_context="INTERRUPTION RESUME MODE:
This branch/worktree already existed before this launch and the task was not marked halted.
The conductor assigned this worktree to you: ${wt}
Assume a previous agent run was interrupted. Do not restart blindly and do not choose a different worktree.

Before editing:
1. cd into ${wt} and inspect the current state.
2. Read .claude/state.json in the main checkout if useful; the matching inflight item may include last_stdout_line, worktree, agent_workdir, and last_update.
3. Run 'git status --short' and inspect existing diffs before making changes.
4. Infer the last completed step from current files, git status, recent commits, and any PR/result comments.
5. Continue from the earliest unsafe/incomplete step. Keep useful existing work, remove only clear junk, and do not overwrite prior progress without checking it.

Current git status snapshot:
${status_snapshot:-<clean>}

Recent commits snapshot:
${commit_snapshot:-<none>}
"
else
  resume_context="Fresh task run. The conductor assigned this worktree to you: ${wt}."
fi

prompt="You are one developer. The orchestrator/conductor assigned this working directory for this task: ${wt}
Work ONLY inside that directory. Do NOT touch other paths.

${resume_context}

Implement this task, scoped and matching existing style:

${task}

Steps:
1. cd into ${wt} first. Verify with pwd.
2. Run './mill __.compile' from inside ${wt} to generate fresh SemanticDB. This initializes
   scala-semantic analysis tools for the worktree's code. Do this before any code analysis.
3. Implement the task. Touch only files required by the task.
4. No scratch files, build artifacts (target/, out/, .bsp/, *.class), notes, or unrelated edits.
5. Review your own changes with 'git diff' and 'git status --porcelain' before finishing.
   Remove any junk or out-of-scope files.
6. Build and test: run './mill __.test' from inside ${wt}. For a full quality check run
   './mill prePush' (clean + fmt check + test). Fix any errors before proceeding.
7. Run './tree2m --auto ${branch} <Conventional-Commit message>' from inside ${wt} to commit, push, and merge.
8. Print the PR URL on the last line of your output.

Output discipline:
- Keep stdout quiet. Print only errors, prompts/questions that need attention, final summaries,
  PR/result URLs, and other explicitly important status.
- Treat stdout as a parent-agent control channel: anything printed there should be something the
  parent/conductor may need to react to.
- Do not stream routine command chatter, progress narration, full diffs, issue bodies, or bulk logs
  unless they are needed to diagnose a failure.

If tree2m fails because the PR is not mergeable (merge conflict), print exactly:
MERGE_CONFLICT: <list conflicting files>
Then stop. Do NOT attempt to resolve the conflict — the orchestrator handles it."

run_and_capture() {
  local output_file="$1"
  shift

  set +e
  "$@" 2>&1 | while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line" >> "$output_file"
    "$script_dir/agent-status.sh" "$branch" --last-stdout-line "$line" || true
  done
  local status="${PIPESTATUS[0]}"
  set -e
  return "$status"
}

output_file="$(mktemp)"
trap 'rm -f "$output_file"; [[ -n "${agy_wt:-}" ]] && rm -f "$agy_wt"' EXIT

engine_exit=0
case "$engine" in
  codex)
    "$script_dir/agent-status.sh" "$branch" \
      --agent-workdir "$wt" \
      --last-stdout-line "starting codex worker in $wt" || true
    codex_cmd=(codex exec)
    [[ -z "$model" ]] || codex_cmd+=(--model "$model")
    codex_cmd+=(--dangerously-bypass-approvals-and-sandbox "$prompt")
    run_and_capture "$output_file" bash -c 'cd "$1" && shift && "$@"' bash "$wt" "${codex_cmd[@]}" || engine_exit=$?
    ;;
  agy)
    # agy --add-dir silently falls back when any path component starts with '.'.
    # .worktrees/<branch> has a hidden component, so use a visible symlink instead.
    agy_wt="${repo_root}/worktrees/${branch}"
    ln -sfn "$wt" "$agy_wt"
    "$script_dir/agent-status.sh" "$branch" \
      --agent-workdir "$agy_wt" \
      --last-stdout-line "starting agy worker with add-dir $agy_wt" || true
    agy_cmd=(agy --print --add-dir "$agy_wt")
    [[ -z "$model" ]] || agy_cmd+=(--model "$model")
    agy_cmd+=(--dangerously-skip-permissions "$prompt")
    run_and_capture "$output_file" "${agy_cmd[@]}" || engine_exit=$?
    ;;
  *)
    echo "unknown engine: $engine" >&2; exit 2 ;;
esac

output="$(cat "$output_file")"

# ── 3. Parse result ───────────────────────────────────────────────────────────
if echo "$output" | grep -qi "^MERGE_CONFLICT\|not mergeable\|merge conflict"; then
  conflict_detail=$(echo "$output" | grep -i "MERGE_CONFLICT\|conflict" | head -3 | tr '\n' ' ' | sed 's/"/\\"/g')
  "$script_dir/agent-status.sh" "$branch" --last-stdout-line "merge conflict: $conflict_detail" || true
  printf '{"status":"merge_conflict","branch":"%s","pr_url":"","details":"%s"}\n' \
    "$branch" "$conflict_detail"
  exit 0
fi

if [[ $engine_exit -ne 0 ]]; then
  error_detail=$(echo "$output" | tail -5 | tr '\n' ' ' | sed 's/"/\\"/g')
  "$script_dir/agent-status.sh" "$branch" --last-stdout-line "worker error: $error_detail" || true
  printf '{"status":"error","branch":"%s","pr_url":"","details":"%s"}\n' \
    "$branch" "$error_detail"
  exit 1
fi

pr_url=$(echo "$output" | grep -oE 'https://github\.com/[^/]+/[^/]+/pull/[0-9]+' | tail -1 || true)

# Cleanup worktree on success (tree2m removes the branch; worktree may already be gone)
git -C "$repo_root" worktree remove --force "$wt" 2>/dev/null || true
"$script_dir/agent-status.sh" "$branch" --last-stdout-line "worker completed successfully" || true

printf '{"status":"success","branch":"%s","pr_url":"%s","details":"engine=%s model=%s"}\n' \
  "$branch" "${pr_url:-}" "$engine" "${model:-default}"
