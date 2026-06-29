#!/usr/bin/env bash

# Resume shipping a branch that is ALREADY committed + pushed (e.g. after agent-ship.sh/tree2m
# aborted at "nothing to commit" following a manual fixup). Waits for CI, squash-merges, removes
# the worktree. Same final gate as tree2m but skips the commit/push phase.
#
# Usage: scripts/agent-merge.sh <branch>

set -euo pipefail

branch="${1:?branch required}"
repo_root="$(git rev-parse --show-toplevel)"
wt="${repo_root}/.claude/worktrees/${branch}"

cd "$repo_root"
# Remove worktree BEFORE merge so gh --delete-branch can drop the local branch
# (git refuses to delete a branch still checked out in a worktree).
git worktree remove --force "$wt" 2>/dev/null || true
rtk scripts/check-push-workflow.sh --branch "$branch"
rtk gh pr merge "$branch" --squash --delete-branch
echo "MERGED $branch"
