#!/usr/bin/env bash

# Render a release-notes BODY (Markdown) for the commits in a git range, keeping ONLY user-facing
# Conventional-Commit types and dropping the noise (docs, refactor, test, chore, ci, build, style).
#
# Usage:
#   scripts/changelog.sh <range>          # e.g. v0.1.6..v0.2.0   or   v0.2.0 (all up to a tag)
#
# Source of truth is commit subjects. With squash-merged PRs each subject is the PR title, so write
# Conventional-Commit PR titles (`feat:`, `fix:`, `feat(scope)!: …`) — that is what gets kept and
# grouped here. Anything not matching a kept type is intentionally omitted from release notes.

set -euo pipefail

range="${1:?usage: changelog.sh <range>}"

# Kept types → section heading. Order here is the order sections print in.
breaking=()
feat=()
fix=()
perf=()

# Conventional-Commit subject: type(scope)!: description. Kept in a var so bash parses it as a regex.
cc_re='^([a-z]+)(\(([^)]*)\))?(!)?:[[:space:]]+(.*)$'

while IFS= read -r subject; do
  [[ -z "$subject" ]] && continue
  if [[ "$subject" =~ $cc_re ]]; then
    type="${BASH_REMATCH[1]}"
    scope="${BASH_REMATCH[3]}"
    bang="${BASH_REMATCH[4]}"
    desc="${BASH_REMATCH[5]}"
    line="$desc"
    [[ -n "$scope" ]] && line="**$scope:** $desc"
    if [[ -n "$bang" ]]; then
      breaking+=("$line")
      continue
    fi
    case "$type" in
      feat) feat+=("$line") ;;
      fix) fix+=("$line") ;;
      perf) perf+=("$line") ;;
      *) : ;; # docs, refactor, test, chore, ci, build, style, … → omitted
    esac
  fi
  # Non-Conventional subjects are omitted too (enforce CC titles for things that should appear).
done < <(git log --no-merges --format='%s' "$range")

emit_section() {
  local title="$1"; shift
  local items=("$@")
  [[ ${#items[@]} -eq 0 ]] && return 0
  printf '### %s\n\n' "$title"
  printf -- '- %s\n' "${items[@]}"
  printf '\n'
}

if [[ ${#breaking[@]} -eq 0 && ${#feat[@]} -eq 0 && ${#fix[@]} -eq 0 && ${#perf[@]} -eq 0 ]]; then
  echo "Maintenance release — no user-facing changes."
  exit 0
fi

# `${arr[@]+"${arr[@]}"}` expands safely to nothing when the array is empty (bash 3.2 + set -u).
emit_section "⚠️ Breaking changes" ${breaking[@]+"${breaking[@]}"}
emit_section "✨ Features" ${feat[@]+"${feat[@]}"}
emit_section "🐛 Fixes" ${fix[@]+"${fix[@]}"}
emit_section "⚡ Performance" ${perf[@]+"${perf[@]}"}
