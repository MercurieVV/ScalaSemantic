#!/usr/bin/env bash

# Wait for the latest push-triggered CI run on the current branch to finish; exit nonzero on failure.
# Then report the latest version published to Maven Central.
#
# Usage:
#   scripts/check-push-workflow.sh [--workflow NAME] [--branch BRANCH] [--repo OWNER/REPO]
# Defaults come from scripts/config.sh (CI_WORKFLOW_NAME, REPO) and the current git branch.

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/config.sh"

workflow_name="$CI_WORKFLOW_NAME"
branch_name=""
repo="$REPO"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workflow) workflow_name="${2:-}"; shift 2 ;;
    --branch) branch_name="${2:-}"; shift 2 ;;
    --repo) repo="${2:-}"; shift 2 ;;
    -h|--help) sed -n '3,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }
require_cmd gh
require_cmd git
require_cmd curl

[[ -n "$branch_name" ]] || branch_name="$(git branch --show-current)"
[[ -n "$branch_name" ]] || { echo "Could not determine branch. Pass --branch." >&2; exit 1; }

run_id="$(gh run list --repo "$repo" --workflow "$workflow_name" --branch "$branch_name" \
  --event push --limit 1 --json databaseId --jq '.[0].databaseId')"

# Fall back to pull_request-triggered run if push run is absent or already failed
if [[ -z "$run_id" || "$run_id" == "null" ]]; then
  run_id="$(gh run list --repo "$repo" --workflow "$workflow_name" --branch "$branch_name" \
    --event pull_request --limit 1 --json databaseId --jq '.[0].databaseId')"
fi

if [[ -z "$run_id" || "$run_id" == "null" ]]; then
  echo "No push-triggered run of '$workflow_name' on '$branch_name'." >&2
  exit 1
fi

status="$(gh run view "$run_id" --repo "$repo" --json status --jq '.status')"
if [[ "$status" == "completed" ]]; then
  conclusion="$(gh run view "$run_id" --repo "$repo" --json conclusion --jq '.conclusion')"
  [[ "$conclusion" == "success" ]] || { echo "CI failed: $conclusion (run $run_id)" >&2; exit 1; }
else
  tmp="$(mktemp)"
  gh run watch "$run_id" --repo "$repo" --exit-status >"$tmp" 2>&1 || { cat "$tmp" >&2; rm -f "$tmp"; exit 1; }
  rm -f "$tmp"
fi
