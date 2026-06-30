#!/usr/bin/env bash

# Update live worker status in .claude/state.json.
#
# Usage:
#   scripts/agent-status.sh <branch> [--worker-id ID] [--worktree PATH] \
#     [--agent-workdir PATH] [--last-stdout-line LINE]
#
# The update is best-effort: missing state files or missing inflight records are
# not fatal, because workers should not fail just because the conductor state is
# absent or stale.

set -euo pipefail

branch="${1:?branch required}"
shift

worker_id=""
worktree=""
agent_workdir=""
last_stdout_line=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --worker-id)
      worker_id="${2:?--worker-id requires a value}"
      shift 2
      ;;
    --worktree)
      worktree="${2:?--worktree requires a value}"
      shift 2
      ;;
    --agent-workdir)
      agent_workdir="${2:?--agent-workdir requires a value}"
      shift 2
      ;;
    --last-stdout-line)
      last_stdout_line="${2:?--last-stdout-line requires a value}"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
state_root="$repo_root"
case "$repo_root" in
  */.worktrees/*)
    state_root="${repo_root%%/.worktrees/*}"
    ;;
esac
state_file="${state_root}/.claude/state.json"

[[ -f "$state_file" ]] || exit 0

lock_dir="${state_file}.lock.d"
locked=0
for _ in {1..50}; do
  if mkdir "$lock_dir" 2>/dev/null; then
    locked=1
    break
  fi
  sleep 0.1
done

[[ "$locked" -eq 1 ]] || exit 0
trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT

export AGENT_STATUS_BRANCH="$branch"
export AGENT_STATUS_WORKER_ID="$worker_id"
export AGENT_STATUS_WORKTREE="$worktree"
export AGENT_STATUS_AGENT_WORKDIR="$agent_workdir"
export AGENT_STATUS_LAST_STDOUT_LINE="$last_stdout_line"

python3 - "$state_file" <<'PY'
import datetime
import json
import os
import pathlib
import sys
import tempfile

state_path = pathlib.Path(sys.argv[1])
branch = os.environ["AGENT_STATUS_BRANCH"]
worker_id = os.environ["AGENT_STATUS_WORKER_ID"]
worktree = os.environ["AGENT_STATUS_WORKTREE"]
agent_workdir = os.environ["AGENT_STATUS_AGENT_WORKDIR"]
last_stdout_line = os.environ["AGENT_STATUS_LAST_STDOUT_LINE"]
now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

try:
    with state_path.open("r", encoding="utf-8") as fh:
        state = json.load(fh)
except (OSError, json.JSONDecodeError):
    sys.exit(0)

updated = False
for task in state.get("inflight", []):
    branch_matches = task.get("branch") == branch
    worker_matches = bool(worker_id) and task.get("worker_id") == worker_id
    if not branch_matches and not worker_matches:
        continue

    if worktree:
        task["worktree"] = worktree
    if agent_workdir:
        task["agent_workdir"] = agent_workdir
    if last_stdout_line:
        task["last_stdout_line"] = last_stdout_line
    task["last_update"] = now
    updated = True

if not updated:
    sys.exit(0)

state["timestamp"] = now
tmp_fd, tmp_name = tempfile.mkstemp(prefix=state_path.name, suffix=".tmp", dir=state_path.parent)
try:
    with os.fdopen(tmp_fd, "w", encoding="utf-8") as fh:
        json.dump(state, fh, indent=2)
        fh.write("\n")
    os.replace(tmp_name, state_path)
except OSError:
    try:
        os.unlink(tmp_name)
    except OSError:
        pass
    sys.exit(0)
PY
