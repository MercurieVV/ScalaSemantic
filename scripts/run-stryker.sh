#!/usr/bin/env bash
# Run stryker4s mutation testing in an isolated, throwaway git worktree.
#
# Why a worktree: a stryker run recompiles the whole project and copies the module into a
# sandbox under <module>/target — a lot of churn. Doing it in a separate worktree keeps your
# main checkout's target/ and working tree untouched, and keeps the sandbox copies out of the
# parent directory. The worktree lives at .worktrees/stryker4s-<name> (already gitignored).
#
# It checks out the current HEAD, so only COMMITTED code is mutated — uncommitted edits in your
# main tree are NOT included (the script warns if you have any).
#
# Usage:
#   scripts/run-stryker.sh <name>
# e.g. scripts/run-stryker.sh pr70   ->   .worktrees/stryker4s-pr70
#
# On success it hands the fresh stryker JSON report to scripts/mutation-summary.sh, which
# regenerates the committed mutation result file(s) in the main repo. Noisy sbt output is
# filtered to errors + the run summary; the full log is kept in the worktree at stryker-run.log.
set -euo pipefail

name="${1:-}"
if [[ -z "$name" ]]; then
  echo "usage: scripts/run-stryker.sh <name>" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# Use rtk (token-killer proxy) for git when available, plain git otherwise.
GIT="git"
command -v rtk >/dev/null 2>&1 && GIT="rtk git"

wt=".worktrees/stryker4s-$name"
abs_wt="$repo_root/$wt"
head_sha="$(git rev-parse HEAD)"

# Warn if the working tree has changes that the HEAD-based worktree won't see.
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "warning: uncommitted changes in the main tree are NOT included (worktree runs at $head_sha)" >&2
fi

# Create the worktree, or refresh an existing one to the current HEAD (fast incremental reuse).
if git worktree list --porcelain | grep -qx "worktree $abs_wt"; then
  echo "reusing worktree $wt (syncing to $head_sha)"
  $GIT -C "$abs_wt" checkout --quiet --detach "$head_sha"
else
  echo "creating worktree $wt at $head_sha"
  $GIT worktree add --quiet --detach "$wt" "$head_sha"
fi

# Run stryker in the worktree. Capture full output to a log; surface only errors + the summary.
log="$abs_wt/stryker-run.log"
echo "running stryker4s in $wt (full log: $log) ..."
set +e
(cd "$abs_wt" && STRYKER=1 sbt -Dstryker=true "analysis/stryker") >"$log" 2>&1
status=$?
set -e

grep -iE "error|exception|failed|mutation score|no code coverage|detected as static|Written JSON report|elapsed time" \
  "$log" | tail -40 || true

if [[ $status -ne 0 ]]; then
  echo "stryker failed (exit $status). Full log: $log" >&2
  exit $status
fi

# Copy the freshest JSON report from the worktree back to the main repo's mutation-report.json.
report="$(find "$abs_wt/analysis/target/stryker4s-report" -name report.json -print0 2>/dev/null \
  | xargs -0 ls -t 2>/dev/null | head -1 || true)"
if [[ -z "$report" || ! -f "$report" ]]; then
  echo "error: no report.json produced under $wt/analysis/target/stryker4s-report" >&2
  exit 1
fi

"$repo_root/scripts/mutation-summary.sh" "$report"
echo "done. worktree kept at $wt (rerun reuses it; remove with: git worktree remove $wt)"