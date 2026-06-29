#!/usr/bin/env bash

# Emit the compact junk/scope signal for a finished worker's worktree, in ONE call, so the
# sanity-check gate spends minimal tokens. Shows changed/untracked files (porcelain), the
# per-file change stat vs origin/master, and flags any unusually large added files.
#
# Usage: scripts/worktree-diff.sh <worktree-path>

set -euo pipefail

wt="${1:?worktree path required}"
[[ -d "$wt" ]] || { echo "worktree not found: $wt" >&2; exit 1; }

echo "## status (porcelain: ?? = untracked/new)"
git -C "$wt" status --porcelain

echo "## diff --stat vs origin/master"
git -C "$wt" diff --stat origin/master || true

echo "## large additions (>200 KB, possible artifacts)"
git -C "$wt" status --porcelain | awk '{print $2}' | while read -r f; do
  [[ -f "$wt/$f" ]] || continue
  sz=$(wc -c <"$wt/$f")
  (( sz > 204800 )) && printf '%8d B  %s\n' "$sz" "$f"
done