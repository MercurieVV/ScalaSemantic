#!/usr/bin/env bash
# Regenerate regression-comparable mutation summaries from a Stryker4s JSON report.
#
# Stryker emits the machine-readable report (mutation-testing-elements schema) at
#   analysis/target/stryker4s-report/<timestamp>/report.json
# (the `json` reporter in stryker4s.conf). This script flattens it into two committed,
# diff-friendly text files so mutation regressions show up as plain `git diff` line changes:
#   mutation-survivors.txt    — Survived mutants (covered by a test, but no test kills them)
#   mutation-nocoverage.txt   — NoCoverage mutants (no test even exercises the line)
#
# Lines are sorted by file:line:col so the output is stable across runs and the only diffs
# are genuine status changes. Usage:
#   scripts/mutation-summary.sh [path/to/report.json]
# With no arg, the newest report under analysis/target/stryker4s-report is used.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

report="${1:-}"
if [[ -z "$report" ]]; then
  report="$(ls -t analysis/target/stryker4s-report/*/report.json 2>/dev/null | head -1 || true)"
fi
if [[ -z "$report" || ! -f "$report" ]]; then
  echo "error: no report.json found (looked under analysis/target/stryker4s-report/*/)" >&2
  echo "       run 'sbt -Dstryker=true \"analysis/stryker\"' first, or pass the report path explicitly." >&2
  exit 1
fi

emit() { # <status> <out-file> <human-label>
  local status="$1" out="$2" label="$3"
  # Flatten files[].mutants[] of the given status to `file:line:col  [Mutator] -> replacement`
  local body
  body="$(jq -r --arg s "$status" '
    .files
    | to_entries[]
    | .key as $f
    | .value.mutants[]
    | select(.status == $s)
    # replacements can span multiple lines; flatten to keep one mutant per line (stable diffs)
    | (.replacement | gsub("\\s+"; " ") | gsub("^ | $"; "")) as $repl
    | "\($f):\(.location.start.line):\(.location.start.column)  [\(.mutatorName)] -> \($repl)"
  ' "$report" | LC_ALL=C sort -t: -k1,1 -k2,2n -k3,3n)"

  local count
  count="$(printf '%s\n' "$body" | grep -c . || true)"

  {
    echo "# $label mutants: $count"
    echo "# generated from $report"
    echo "#"
    echo "# by file:"
    printf '%s\n' "$body" | sed -E 's/^([^:]+):.*/\1/' | LC_ALL=C sort | uniq -c | sort -rn \
      | sed 's/^/#   /'
    echo "#"
    printf '%s\n' "$body"
  } > "$out"
  echo "wrote $out ($count $label mutants)"
}

emit "Survived"   mutation-survivors.txt  "Survived"
emit "NoCoverage" mutation-nocoverage.txt "NoCoverage"
