#!/usr/bin/env bash
# Run stryker4s mutation testing.
#
# Default mode runs in an isolated, reusable git worktree so the current checkout's target/
# directories stay untouched. Use --local to run in this checkout and keep all Stryker churn under
# the normal local ./target directories.
#
# Usage:
#   scripts/run-stryker.sh <name>       # .worktrees/stryker4s-<name>
#   scripts/run-stryker.sh --local      # current checkout, ./target
#
# On success it copies the stryker JSON report back to mutation-report.json and applies the alert
# rules in scripts/mutation-summary.sh. The full sbt log is kept beside the run.
set -euo pipefail

usage() {
  sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'
}

local_run=0
name=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) local_run=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$name" ]]; then
        echo "unexpected extra argument: $1" >&2
        usage >&2
        exit 2
      fi
      name="$1"
      shift
      ;;
  esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ "$local_run" -eq 0 && -z "$name" ]]; then
  echo "usage: scripts/run-stryker.sh <name>" >&2
  echo "       scripts/run-stryker.sh --local" >&2
  exit 2
fi
if [[ "$local_run" -eq 1 && -n "$name" ]]; then
  echo "error: --local does not take a worktree name" >&2
  exit 2
fi

GIT="git"
command -v rtk >/dev/null 2>&1 && GIT="rtk git"

run_dir="$repo_root"
run_label="local checkout"
if [[ "$local_run" -eq 0 ]]; then
  wt=".worktrees/stryker4s-$name"
  abs_wt="$repo_root/$wt"
  head_sha="$(git rev-parse HEAD)"

  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "warning: uncommitted changes in the main tree are NOT included (worktree runs at $head_sha)" >&2
  fi

  if git worktree list --porcelain | grep -qx "worktree $abs_wt"; then
    echo "reusing worktree $wt (syncing to $head_sha)"
    $GIT -C "$abs_wt" checkout --quiet --detach "$head_sha"
  else
    echo "creating worktree $wt at $head_sha"
    $GIT worktree add --quiet --detach "$wt" "$head_sha"
  fi

  run_dir="$abs_wt"
  run_label="$wt"
fi

log="$run_dir/stryker-run.log"
if [[ "$local_run" -eq 1 ]]; then
  mkdir -p "$repo_root/target"
  log="$repo_root/target/stryker-run.log"
fi

echo "running stryker4s in $run_label (full log: $log) ..."
set +e
(cd "$run_dir" && STRYKER=1 sbt -Dstryker=true "analysis/stryker") >"$log" 2>&1
status=$?
set -e

grep -iE "error|exception|failed|mutation score|no code coverage|detected as static|Written JSON report|elapsed time" \
  "$log" | tail -40 || true

if [[ $status -ne 0 ]]; then
  echo "stryker failed (exit $status). Full log: $log" >&2
  exit $status
fi

report="$(find "$run_dir/analysis/target/stryker4s-report" -name report.json -print0 2>/dev/null \
  | xargs -0 ls -t 2>/dev/null | head -1 || true)"
if [[ -z "$report" || ! -f "$report" ]]; then
  echo "error: no report.json produced under $run_label/analysis/target/stryker4s-report" >&2
  exit 1
fi

"$repo_root/scripts/mutation-summary.sh" "$report"
if [[ "$local_run" -eq 1 ]]; then
  echo "done. local Stryker output kept under analysis/target and target/stryker-run.log"
else
  echo "done. worktree kept at $wt (rerun reuses it; remove with: git worktree remove $wt)"
fi
