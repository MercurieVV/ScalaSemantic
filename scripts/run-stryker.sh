#!/usr/bin/env bash
# Run stryker4s mutation testing (Mill-side).
#
# stryker4s has no Maven Central release of its Mill plugin yet, so vendor/stryker4s-mill/ carries
# a pre-built local snapshot (jar + ivy.xml only, ~1.9M — built once from stryker4s commit 86aa9ed1,
# past stryker-mutator/stryker4s#2068 "handle messages larger than socket buffer", which fixed a
# reproducible InitialTestRunFailedException that blocked every earlier attempt at this) — installed
# into ~/.ivy2/local at run time, no sbt/network build step needed. To refresh it: clone stryker4s,
# checkout the desired commit, run `sbt publishMillLocal`, then copy the resulting
# ~/.ivy2/local/io.stryker-mutator/*/0.0.0-TEST-SNAPSHOT/{jars,ivys} dirs into vendor/stryker4s-mill/
# (only the Scala-3, non-test artifacts: mill-stryker4s_mill1_3, stryker4s-{core,api}_3,
# stryker4s-testrunner{,-api}_3 — everything else in that tree is either test-scoped or cross-built
# for Scala 2 we don't use).
#
# This script then patches an isolated worktree copy of build.mill with
# scripts/generate-stryker-overlay.py (never the committed build.mill — see that script's docstring
# and docs/MILL_MIGRATION.md §10 item 3 for why).
#
# Default mode runs in an isolated, reusable git worktree so the current checkout's out/
# directories stay untouched. Use --local to run in this checkout and keep all Stryker churn under
# the normal local ./out directories.
#
# Usage:
#   scripts/run-stryker.sh <name>                         # .worktrees/stryker4s-<name>
#   scripts/run-stryker.sh --module core <name>           # one module in a worktree
#   scripts/run-stryker.sh --module all <name>            # core, analysis, and mcp
#   scripts/run-stryker.sh --local --module analysis      # current checkout, ./out
#
# On success it copies the stryker JSON report back to mutation-report.json and applies the alert
# rules in scripts/mutation-summary.sh. The full Mill log is kept beside the run.
set -euo pipefail

usage() {
  sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'
}

STRYKER4S_PLUGIN_VERSION="0.0.0-TEST-SNAPSHOT"
STRYKER4S_PLUGIN_ARTIFACT="mill-stryker4s_mill1_3"

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

# --local patches this checkout's build.mill in place (safe on an ephemeral CI runner, but leaves a
# stray diff on a developer's real checkout) — always restore the exact pre-run file on exit,
# success or failure.
build_mill_backup=""
if [[ "$local_run" -eq 1 ]]; then
  build_mill_backup="$(mktemp)"
  cp "$repo_root/build.mill" "$build_mill_backup"
  trap 'cp "$build_mill_backup" "$repo_root/build.mill" 2>/dev/null || true; rm -f "$build_mill_backup" 2>/dev/null || true' EXIT
fi

GIT="git"
command -v rtk >/dev/null 2>&1 && GIT="rtk git"

# --- install the vendored stryker4s Mill plugin snapshot into ~/.ivy2/local -------------------

ensure_stryker_plugin_installed() {
  local marker="$HOME/.ivy2/local/io.stryker-mutator/$STRYKER4S_PLUGIN_ARTIFACT/$STRYKER4S_PLUGIN_VERSION/jars/$STRYKER4S_PLUGIN_ARTIFACT.jar"
  if [[ -f "$marker" ]]; then
    echo "stryker4s Mill plugin already installed ($marker)"
    return
  fi
  local vendor_dir="$repo_root/vendor/stryker4s-mill/io.stryker-mutator"
  if [[ ! -d "$vendor_dir" ]]; then
    echo "error: no vendored plugin at $vendor_dir" >&2
    exit 1
  fi
  echo "installing vendored stryker4s Mill plugin into ~/.ivy2/local ..."
  mkdir -p "$HOME/.ivy2/local"
  cp -r "$vendor_dir/." "$HOME/.ivy2/local/io.stryker-mutator/"
  if [[ ! -f "$marker" ]]; then
    echo "error: installing the vendored plugin did not produce $marker" >&2
    exit 1
  fi
}

ensure_stryker_plugin_installed

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

# Patch the worktree's build.mill with the *Stryker overlay objects (never the committed build.mill).
python3 "$repo_root/scripts/generate-stryker-overlay.py" "$run_dir/build.mill"

log="$run_dir/stryker-run.log"
if [[ "$local_run" -eq 1 ]]; then
  mkdir -p "$repo_root/out"
  log="$repo_root/out/stryker-run.log"
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
  # Patterns are repo-root-relative (see --base-dir in run_module below), so each glob is prefixed
  # with the module's own directory name.
  case "$1" in
    analysis)
      printf '%s\n' \
        "analysis/src/main/scala/**/*.scala" \
        "!analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/DuplicationAnalyzer.scala"
      ;;
    core)
      printf '%s\n' "core/src/main/scala/**/*.scala"
      ;;
    mcp)
      printf '%s\n' \
        "mcp/src/main/scala/**/*.scala" \
        "!mcp/src/main/scala/com/github/mercurievv/scalasemantic/Main.scala"
      ;;
  esac
}

run_module() {
  local target_module="$1"
  local module_log="$log"
  if [[ "$module" == "all" ]]; then
    module_log="${log%.log}-$target_module.log"
  fi

  local args=()
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
  # The Mill plugin hardcodes baseDir = the mutated module's own directory (MillConfigSource sets
  # it from `moduleDir`), so the forked test-runner subprocess's cwd is e.g. `mcp/`, not the repo
  # root. Dogfood tests load `SemanticIndex.fromProject(".")` expecting cwd = repo root (see
  # CLAUDE.md); under the module-dir cwd that resolves to an empty index, so every SemanticDB-backed
  # assertion silently returns zero results and the whole suite reports Status.Failure with zero
  # individual test failures recorded — surfacing only as stryker4s's generic
  # `InitialTestRunFailedException`, with no diagnostic detail anywhere in its own logs. `--base-dir`
  # on the CLI (ConfigOrder 5) outranks the Mill plugin's own baseDir config source (ConfigOrder 15),
  # so this overrides it back to the repo root, matching testForked's behavior.
  args+=("--base-dir" "$run_dir")

  echo "running module $target_module (log: $module_log) ..."
  set +e
  (cd "$run_dir" && ./mill "${target_module}Stryker.stryker" "${args[@]}") >"$module_log" 2>&1
  local status=$?
  set -e

  grep -iE "error|exception|failed|mutation score|no code coverage|detected as static|Written JSON report|elapsed time" \
    "$module_log" | tail -40 || true

  if [[ $status -ne 0 ]]; then
    echo "stryker failed for $target_module (exit $status). Full log: $module_log" >&2
    exit $status
  fi

  # Reports land relative to --base-dir ("$run_dir", the repo root), not the mutated module's own
  # dir, since run_module now passes --base-dir uniformly (see the --base-dir comment above).
  report="$(find "$run_dir/target/stryker4s-report" -name report.json -print0 2>/dev/null \
    | xargs -0 ls -t 2>/dev/null | head -1 || true)"
  if [[ -z "$report" || ! -f "$report" ]]; then
    echo "error: no report.json produced under $run_label/target/stryker4s-report" >&2
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
  echo "done. local Stryker output kept under */target and out/stryker-run*.log"
else
  echo "done. worktree kept at $wt (rerun reuses it; remove with: git worktree remove $wt)"
fi
