#!/usr/bin/env bash
# Copy the Stryker4s JSON report to a stable path and print alert categories.
#
# Stryker emits the machine-readable report (mutation-testing-elements schema) at
#   target/stryker4s-report/<timestamp>/report.json   (the `json` reporter)
# relative to --base-dir (the repo root, see run-stryker.sh), under the gitignored target/ dir.
# Pretty-print it to MUTATION_REPORT_DEST, or to the historical repo-root mutation-report.json when
# that variable is unset.
# Usage:
#   scripts/mutation-summary.sh [path/to/report.json]
# With no arg, the newest report under target/stryker4s-report is used.
#
# Alerts are intentionally stricter than Stryker's built-in threshold reporting:
#   - mutation score below MUTATION_SCORE_ALERT_THRESHOLD, or report thresholds.high when unset
#   - surviving mutants with a mutator in MUTATION_ALERT_MUTATORS
set -euo pipefail

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: missing required command: $1" >&2
    exit 1
  }
}

require_cmd jq

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

alert_mutators="${MUTATION_ALERT_MUTATORS:-ConditionalExpression,EqualityOperator,LogicalOperator}"

module_label="${MUTATION_MODULE:-mutation}"
report="${1:-}"
if [[ -z "$report" ]]; then
  report="$(find target/stryker4s-report -path '*/report.json' -print0 2>/dev/null \
    | xargs -0 ls -t 2>/dev/null | head -1 || true)"
fi
if [[ -z "$report" || ! -f "$report" ]]; then
  echo "error: no report.json found (looked under target/stryker4s-report/*/)" >&2
  echo "       run 'scripts/run-stryker.sh --local --module <module>' first, or pass the report path explicitly." >&2
  exit 1
fi

dest="${MUTATION_REPORT_DEST:-mutation-report.json}"
dest_dir="$(dirname "$dest")"
if [[ "$dest_dir" != "." ]]; then
  mkdir -p "$dest_dir"
fi
tmp_dest="$(mktemp "$dest_dir/.report.json.XXXXXX")"
trap 'rm -f "$tmp_dest"' EXIT
jq . "$report" > "$tmp_dest"
mv "$tmp_dest" "$dest"
trap - EXIT
echo "wrote $dest from $report"

score_line="$(jq -r '
  [.files[]?.mutants[]?] as $mutants
  | ($mutants | map(select(.status == "Killed" or .status == "Timeout")) | length) as $detected
  | ($mutants | map(select(.status == "Killed" or .status == "Timeout" or .status == "Survived" or .status == "NoCoverage")) | length) as $scored
  | (if $scored == 0 then 100 else ((10000 * $detected / $scored) | round / 100) end) as $score
  | [$score, $detected, $scored] | @tsv
' "$dest")"
IFS=$'\t' read -r mutation_score detected_count scored_count <<<"$score_line"

score_threshold="${MUTATION_SCORE_ALERT_THRESHOLD:-}"
if [[ -z "$score_threshold" ]]; then
  score_threshold="$(jq -r '.thresholds.high // .thresholds.low // 80' "$dest")"
fi

echo "${module_label} mutation score: ${mutation_score}% (${detected_count}/${scored_count} detected, alert threshold ${score_threshold}%)"

status_counts="$(jq -r '
  [.files[]?.mutants[]?]
  | group_by(.status)
  | map({status: .[0].status, count: length})
  | sort_by(.status)
  | .[]
  | "  \(.status): \(.count)"
' "$dest")"
if [[ -n "$status_counts" ]]; then
  echo "mutants by status:"
  echo "$status_counts"
fi

survivor_counts="$(jq -r '
  [.files[]?.mutants[]? | select(.status == "Survived")]
  | group_by(.mutatorName)
  | map({mutator: .[0].mutatorName, count: length})
  | sort_by(-.count, .mutator)
  | .[]
  | "  \(.mutator): \(.count)"
' "$dest")"
if [[ -n "$survivor_counts" ]]; then
  echo "surviving mutants by type:"
  echo "$survivor_counts"
fi

error_counts="$(jq -r '
  [.files[]?.mutants[]? | select(.status == "CompileError" or .status == "RuntimeError")]
  | group_by(.status)
  | map({status: .[0].status, count: length})
  | sort_by(.status)
  | .[]
  | "  \(.status): \(.count)"
' "$dest")"
if [[ -n "$error_counts" ]]; then
  echo "error mutants:"
  echo "$error_counts"
fi

score_alert="$(awk -v score="$mutation_score" -v threshold="$score_threshold" 'BEGIN { print (score < threshold ? 1 : 0) }')"
surviving_alerts="$(jq -r --arg mutators "$alert_mutators" '
  ($mutators | split(",") | map(gsub("^ +| +$"; "")) | map(select(length > 0))) as $alertMutators
  | [
      .files
      | to_entries[]
      | .key as $file
      | .value.mutants[]?
      | select(.status == "Survived" and (.mutatorName as $mutator | $alertMutators | index($mutator)))
      | {file: $file, id: .id, mutator: .mutatorName, line: .location.start.line}
    ]
  | group_by(.mutator)
  | map({
      mutator: .[0].mutator,
      count: length,
      examples: (.[0:3] | map("\(.file):\(.line) #\(.id)") | join(", "))
    })
  | sort_by(.mutator)
  | .[]
  | "  \(.mutator): \(.count) (\(.examples))"
' "$dest")"

alert=0
if [[ "$score_alert" == "1" ]]; then
  echo "alert: ${module_label} mutation score ${mutation_score}% is below ${score_threshold}%" >&2
  alert=1
fi
if [[ -n "$surviving_alerts" ]]; then
  echo "alert: surviving mutants match MUTATION_ALERT_MUTATORS=$alert_mutators" >&2
  echo "$surviving_alerts" >&2
  alert=1
fi

exit "$alert"
