#!/usr/bin/env bash

# Configure the GitHub repo for the Sonatype Central release workflow:
#   - set the release secrets (scripts/config.sh RELEASE_SECRETS)
#   - enable vulnerability alerts / dependency graph
#
# Secret values are resolved, in order, from:
#   1. a plain environment variable of the same name (e.g. PGP_SECRET=...)
#   2. an explicit 1Password ref env var OP_<NAME>_REF (e.g. OP_PGP_SECRET_REF=op://Vault/Item/PGP_SECRET)
#   3. a single 1Password item base via --op-item op://VAULT/ITEM, deriving op://VAULT/ITEM/<NAME>
#
# Usage:
#   scripts/setup-gh-repo.sh [--repo OWNER/REPO] [--op-item op://VAULT/ITEM]
# REPO defaults to scripts/config.sh.

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/config.sh"

op_item=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --op-item) op_item="${2:-}"; shift 2 ;;
    -h|--help) sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }
require_cmd gh
require_cmd git
[[ -n "$REPO" ]] || { echo "REPO is empty — set it in config.sh or pass --repo." >&2; exit 1; }

resolve_value() {
  local name="$1" op_ref_name="OP_${1}_REF"
  if [[ -n "${!name:-}" ]]; then
    printf '%s' "${!name}"
  elif [[ -n "${!op_ref_name:-}" ]]; then
    require_cmd op; op read "${!op_ref_name}"
  elif [[ -n "$op_item" ]]; then
    require_cmd op; op read "${op_item}/${name}"
  else
    echo "Missing value for $name. Set \$$name, \$$op_ref_name, or pass --op-item." >&2
    exit 1
  fi
}

for secret in "${RELEASE_SECRETS[@]}"; do
  gh secret set "$secret" --repo "$REPO" --body "$(resolve_value "$secret")"
  echo "Set repo secret $secret"
done

gh api -H "Accept: application/vnd.github+json" --method PUT "/repos/${REPO}/vulnerability-alerts" >/dev/null
echo "Enabled vulnerability alerts and dependency graph"

echo "Done. Repo ${REPO} is ready for tag-driven releases (scripts/bump-version.sh ... --push)."
