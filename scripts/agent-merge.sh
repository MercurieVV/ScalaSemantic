#!/usr/bin/env bash

# Resume shipping a branch that is ALREADY committed + pushed (e.g. agent-ship.sh/tree2m
# aborted at its "nothing to commit" step after a manual fixup was pushed, or CI needed a
# re-check). Waits for the branch's PR checks, squash-merges, deletes the branch, and removes
# the worktree. Same final gate + merge path as tree2m, just without the commit/push phase.
#
# Usage: scripts/agent-merge.sh <branch>
# Exit 0 = merged. Non-zero = checks failed; NOT merged (worktree kept for inspection).

set -euo pipefail

branch="${1:?branch required}"
repo_root="$(git rev-parse --show-toplevel)"
wt="${repo_root}/.worktrees/${branch}"

cd "$repo_root"
rtk scripts/check-push-workflow.sh --branch "$branch"
# Remove the worktree BEFORE merging so gh's --delete-branch can drop the local branch
# (git refuses to delete a branch still checked out in a worktree).
git worktree remove --force "$wt" 2>/dev/null || true
rtk gh pr merge "$branch" --squash --delete-branch
echo "MERGED $branch"
