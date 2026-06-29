#!/usr/bin/env bash

# Create a fresh git worktree for a task branch off the latest origin/master and print its path.
# One call for the "new isolated workspace" phase — used by the conductor (claude engine) and by
# scripts/agent-run.sh (codex/agy engines), so worktree creation is identical everywhere.
#
# Usage: scripts/worktree-new.sh <branch>   # prints the worktree path on stdout

set -euo pipefail

branch="${1:?branch required}"
repo_root="$(git rev-parse --show-toplevel)"
base="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##')"
[[ -n "$base" ]] || base="master"
wt="${repo_root}/worktrees/${branch}"

git -C "$repo_root" fetch origin "$base" --quiet || true
git -C "$repo_root" worktree add -b "$branch" "$wt" "origin/${base}" >&2
echo "$wt"