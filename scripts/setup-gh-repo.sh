#!/usr/bin/env bash

# Configure the GitHub repo for the Sonatype Central release workflow:
#   - set the release secrets (scripts/config.sh RELEASE_SECRETS)
#   - enable vulnerability alerts / dependency graph
#
# Secret values are resolved, in order, from:
#   1. a plain environment variable of the same name (e.g. PGP_SECRET=...)
#   2. an explicit 1Password ref env var OP_<NAME>_REF (e.g. OP_PGP_SECRET_REF=op://Vault/Item/PGP_SECRET)
#   3. a per-group 1Password item: --op-gpg-item (PGP_*) / --op-sonatype-item (SONATYPE_*)
#   4. a single 1Password item base via --op-item, deriving op://VAULT/ITEM/<NAME>
# Item refs also default from scripts/config.sh (OP_ITEM / OP_GPG_ITEM / OP_SONATYPE_ITEM).
#
# Usage:
#   scripts/setup-gh-repo.sh [--repo OWNER/REPO] [--op-item REF] [--op-gpg-item REF] [--op-sonatype-item REF]

eval $(op signin)

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/config.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --op-item) OP_ITEM="${2:-}"; shift 2 ;;
    --op-gpg-item) OP_GPG_ITEM="${2:-}"; shift 2 ;;
    --op-sonatype-item) OP_SONATYPE_ITEM="${2:-}"; shift 2 ;;
    -h|--help) sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }
require_cmd gh
require_cmd git
[[ -n "$REPO" ]] || { echo "REPO is empty — set it in config.sh or pass --repo." >&2; exit 1; }

resolve_value() {
  local name="$1" op_ref_name="OP_${1}_REF" item_base=""
  if [[ -n "${!name:-}" ]]; then
    printf '%s' "${!name}"; return 0
  fi
  if [[ -n "${!op_ref_name:-}" ]]; then
    require_cmd op; op read "${!op_ref_name}"; return 0
  fi
  case "$name" in
    PGP_*) item_base="$OP_GPG_ITEM" ;;
    SONATYPE_*) item_base="$OP_SONATYPE_ITEM" ;;
  esac
  [[ -n "$item_base" ]] || item_base="$OP_ITEM"
  if [[ -n "$item_base" ]]; then
    require_cmd op; op read "${item_base}/${name}"; return 0
  fi
  echo "Missing value for $name. Set \$$name, \$$op_ref_name, --op-item, or --op-gpg-item/--op-sonatype-item." >&2
  exit 1
}

for secret in "${RELEASE_SECRETS[@]}"; do
  value="$(resolve_value "$secret")"
  if [[ "$secret" == "PGP_SECRET" && "${PGP_SECRET_BASE64_ENCODE:-false}" == "true" ]]; then
    # base64-encode the (armored) key so ci-release's `base64 --decode | gpg --import` works.
    value="$(printf '%s' "$value" | base64 | tr -d '\n')"
    echo "  (base64-encoded PGP_SECRET)"
  fi
  printf '%s' "$value" | gh secret set "$secret" --repo "$REPO"
  echo "Set repo secret $secret"
done

gh api -H "Accept: application/vnd.github+json" --method PUT "/repos/${REPO}/vulnerability-alerts" >/dev/null
echo "Enabled vulnerability alerts and dependency graph"

echo "Done. Repo ${REPO} is ready for tag-driven releases (scripts/bump-version.sh patch|minor|major)."
