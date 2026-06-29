#!/usr/bin/env bash

# Ship a worker's worktree AFTER the conductor's sanity check passed.
# Runs ./tree2m (commit -> push -> hooks gate -> CI -> squash-merge), then removes the worktree.
#
# Usage:
#   scripts/agent-ship.sh <branch> [commit-message]
#
# Exit 0 = merged. Non-zero = hooks/CI/push failed; NOT merged (worktree left for inspection).

set -euo pipefail

branch="${1:?branch required}"
message="${2:-${branch}: automated implementation}"

repo_root="$(git rev-parse --show-toplevel)"
wt="${repo_root}/worktrees/${branch}"
[[ -d "$wt" ]] || { echo "worktree not found: $wt" >&2; exit 1; }

if ( cd "$wt" && ./tree2m "$branch" "$message" ); then
  git -C "$repo_root" worktree remove --force "$wt" 2>/dev/null || true
else
  echo "ship failed for $branch; worktree kept at $wt for inspection" >&2
  exit 1
fi