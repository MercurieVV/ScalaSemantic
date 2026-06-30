#!/usr/bin/env bash

# Stage an EXPLICIT set of paths (never `add -A`), commit, push the current/target branch,
# and print the resulting PR file list if a PR exists. For iterating on a feature branch
# without sweeping unrelated working-tree changes and without auto-merging (unlike tree2m).
#
# Usage:
#   scripts/commit-push.sh <branch> "<commit message>" <path> [<path> ...]
#
# Uses rtk to keep output (and token cost) small. Exits nonzero on hook/push failure.

set -euo pipefail

branch="${1:?branch required}"
message="${2:?commit message required}"
shift 2
[[ $# -gt 0 ]] || { echo "at least one path required" >&2; exit 1; }
paths=("$@")

command -v rtk >/dev/null 2>&1 || { echo "Missing required command: rtk" >&2; exit 1; }
remote="origin"

# Switch to / create the branch without disturbing unrelated changes.
current="$(rtk git branch --show-current)"
if [[ "$current" != "$branch" ]]; then
  if rtk git show-ref --verify --quiet "refs/heads/${branch}"; then
    rtk git switch "$branch"
  else
    rtk git switch -c "$branch"
  fi
fi

rtk git add -- "${paths[@]}"
if rtk git diff --cached --quiet; then
  echo "nothing staged for: ${paths[*]}" >&2
  exit 0
fi

rtk git commit -m "$message" >/dev/null
rtk git push --quiet -u "$remote" "$branch"