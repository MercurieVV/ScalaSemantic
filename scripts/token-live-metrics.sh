#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'USAGE'
Usage: scripts/token-live-metrics.sh [raw-runs-json] [aggregate-output-json]

Aggregates live WITH-vs-WITHOUT ScalaSemantic token measurements using the
Scala test harness. The raw input must follow
docs/research/token-metrics-live-runs.sample.json.

Defaults:
  raw-runs-json          docs/research/token-metrics-live-runs.sample.json
  aggregate-output-json  docs/research/token-metrics-live.sample.json
USAGE
  exit 0
fi

raw_runs="${1:-docs/research/token-metrics-live-runs.sample.json}"
output_json="${2:-docs/research/token-metrics-live.sample.json}"

UPDATE_TOKEN_LIVE_METRICS=1 \
TOKEN_LIVE_RUNS="$raw_runs" \
TOKEN_LIVE_OUTPUT="$output_json" \
./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.TokenLiveMetricsSuite
