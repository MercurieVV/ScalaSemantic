#!/usr/bin/env bash
# Copy the Stryker4s JSON report to a committed, stable path.
#
# Stryker emits the machine-readable report (mutation-testing-elements schema) at
#   analysis/target/stryker4s-report/<timestamp>/report.json   (the `json` reporter)
# which lives under the gitignored target/ dir. Copy it verbatim to the repo root so the
# stryker4s json output is committed and tracked:
#   mutation-report.json
# Usage:
#   scripts/mutation-summary.sh [path/to/report.json]
# With no arg, the newest report under analysis/target/stryker4s-report is used.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

report="${1:-}"
if [[ -z "$report" ]]; then
  report="$(find analysis/target/stryker4s-report -name report.json -print0 2>/dev/null \
    | xargs -0 ls -t 2>/dev/null | head -1 || true)"
fi
if [[ -z "$report" || ! -f "$report" ]]; then
  echo "error: no report.json found (looked under analysis/target/stryker4s-report/*/)" >&2
  echo "       run 'sbt -Dstryker=true \"analysis/stryker\"' first, or pass the report path explicitly." >&2
  exit 1
fi

cp "$report" mutation-report.json
echo "wrote mutation-report.json from $report"