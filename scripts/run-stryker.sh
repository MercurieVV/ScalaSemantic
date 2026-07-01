#!/usr/bin/env bash
# Run stryker4s mutation testing.
#
# Default mode runs in an isolated, reusable git worktree so the current checkout's target/
# directories stay untouched. Use --local to run in this checkout and keep all Stryker churn under
# the normal local ./target directories.
#
# Usage:
#   scripts/run-stryker.sh <name>                         # .worktrees/stryker4s-<name>
#   scripts/run-stryker.sh --module core <name>           # one module in a worktree
#   scripts/run-stryker.sh --module all <name>            # core, analysis, and mcp
#   scripts/run-stryker.sh --local --module analysis      # current checkout, ./target
#
# On success it copies the stryker JSON report back to mutation-report.json and applies the alert
# rules in scripts/mutation-summary.sh. The full sbt log is kept beside the run.
set -euo pipefail

usage() {
  sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'
}

local_run=0
module="analysis"
name=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) local_run=1; shift ;;
    --module)
      if [[ $# -lt 2 ]]; then
        echo "error: --module requires analysis, core, mcp, or all" >&2
        exit 2
      fi
      module="$2"
      shift 2
      ;;
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

if [[ "$module" != "analysis" && "$module" != "core" && "$module" != "mcp" && "$module" != "all" ]]; then
  echo "error: unknown module '$module' (expected analysis, core, mcp, or all)" >&2
  exit 2
fi

if [[ "$local_run" -eq 0 && -z "$name" ]]; then
  echo "usage: scripts/run-stryker.sh [--module analysis|core|mcp|all] <name>" >&2
  echo "       scripts/run-stryker.sh --local [--module analysis|core|mcp|all]" >&2
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

module_test_filters() {
  case "$1" in
    analysis)
      printf '%s\n' \
        "com.github.mercurievv.scalasemantic.analysis.CompatSuite" \
        "com.github.mercurievv.scalasemantic.model.ModelsSuite" \
        "com.github.mercurievv.scalasemantic.model.InputTypesSuite" \
        "com.github.mercurievv.scalasemantic.analysis.AnalyzerHelpersSuite" \
        "com.github.mercurievv.scalasemantic.analysis.AnalyzerCoreSuite" \
        "com.github.mercurievv.scalasemantic.analysis.graph.StructureMetricsSuite" \
        "com.github.mercurievv.scalasemantic.analysis.AnalyzerToolsSuite" \
        "com.github.mercurievv.scalasemantic.analysis.DuplicationAnalyzerSuite"
      ;;
    core)
      printf '%s\n' "com.github.mercurievv.scalasemantic.semanticdb.SemanticIndexSuite"
      ;;
    mcp)
      printf '%s\n' \
        "com.github.mercurievv.scalasemantic.mcp.McpSuite" \
        "com.github.mercurievv.scalasemantic.mcp.TokenMetricsSuite"
      ;;
  esac
}

module_mutate_patterns() {
  case "$1" in
    analysis)
      printf '%s\n' \
        "src/main/scala/**/*.scala" \
        "!src/main/scala/com/github/mercurievv/scalasemantic/analysis/DuplicationAnalyzer.scala"
      ;;
    core)
      printf '%s\n' "src/main/scala/**/*.scala"
      ;;
    mcp)
      printf '%s\n' \
        "src/main/scala/**/*.scala" \
        "!src/main/scala/com/github/mercurievv/scalasemantic/Main.scala"
      ;;
  esac
}

run_module() {
  local target_module="$1"
  local module_log="$log"
  if [[ "$module" == "all" ]]; then
    module_log="${log%.log}-$target_module.log"
  fi

  local args=("$target_module / stryker")
  while IFS= read -r pattern; do
    args+=("--mutate" "$pattern")
  done < <(module_mutate_patterns "$target_module")
  while IFS= read -r filter; do
    args+=("--test-filter" "$filter")
  done < <(module_test_filters "$target_module")
  args+=("--reporters" "html" "--reporters" "json")
  args+=("--thresholds.high" "${MUTATION_HIGH_THRESHOLD:-80}")
  args+=("--thresholds.low" "${MUTATION_LOW_THRESHOLD:-60}")
  args+=("--thresholds.break" "${MUTATION_BREAK_THRESHOLD:-0}")
  args+=("--timeout" "${MUTATION_TIMEOUT:-30s}")
  args+=("--timeout-factor" "${MUTATION_TIMEOUT_FACTOR:-3.0}")

  echo "running module $target_module (log: $module_log) ..."
  set +e
  (cd "$run_dir" && STRYKER=1 SBT_OPTS="${SBT_OPTS:--Xmx4G}" sbt --batch -Dstryker=true "${args[*]}") >"$module_log" 2>&1
  local status=$?
  set -e

  grep -iE "error|exception|failed|mutation score|no code coverage|detected as static|Written JSON report|elapsed time" \
    "$module_log" | tail -40 || true

  if [[ $status -ne 0 ]]; then
    echo "stryker failed for $target_module (exit $status). Full log: $module_log" >&2
    exit $status
  fi

  report="$(find "$run_dir/$target_module/target/stryker4s-report" -name report.json -print0 2>/dev/null \
    | xargs -0 ls -t 2>/dev/null | head -1 || true)"
  if [[ -z "$report" || ! -f "$report" ]]; then
    echo "error: no report.json produced under $run_label/$target_module/target/stryker4s-report" >&2
    exit 1
  fi

  summary_status=0
  MUTATION_MODULE="$target_module" "$repo_root/scripts/mutation-summary.sh" "$report" || summary_status=$?
  if [[ "${MUTATION_ALERTS_FAIL:-1}" != "0" && "$summary_status" -ne 0 ]]; then
    exit "$summary_status"
  fi
}

if [[ "$module" == "all" ]]; then
  run_module core
  run_module analysis
  run_module mcp
else
  run_module "$module"
fi
if [[ "$local_run" -eq 1 ]]; then
  echo "done. local Stryker output kept under */target and target/stryker-run*.log"
else
  echo "done. worktree kept at $wt (rerun reuses it; remove with: git worktree remove $wt)"
fi
