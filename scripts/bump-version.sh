#!/usr/bin/env bash

# Create the next vX.Y.Z release tag, which the CI workflow turns into a Sonatype Central release
# (sbt-dynver derives the published version from the tag).
#
# Usage:
#   scripts/bump-version.sh [patch|minor|major] [--push]
#
# Rules:
# - Uses the highest existing v* semver tag if present, else FIRST_VERSION (scripts/config.sh).
# - Creates the tag locally; with --push, also pushes it to origin (which triggers the release).

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/config.sh"

usage() { sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; }

require_clean_git() {
  if [[ -n "$(git status --short)" ]]; then
    echo "Git worktree is not clean. Commit or stash changes before tagging." >&2
    exit 1
  fi
}

latest_version() {
  git tag --list 'v*' | sed 's/^v//' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1
}

next_version() {
  local current="$1" bump="$2" major minor patch
  IFS='.' read -r major minor patch <<<"$current"
  case "$bump" in
    patch) patch=$((patch + 1)) ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    major) major=$((major + 1)); minor=0; patch=0 ;;
    *) echo "Unknown bump type: $bump" >&2; exit 1 ;;
  esac
  printf '%s.%s.%s' "$major" "$minor" "$patch"
}

bump_type="${1:-}"
push_tag="false"
[[ "$bump_type" == "-h" || "$bump_type" == "--help" || -z "$bump_type" ]] && { usage; exit 0; }
shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push) push_tag="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

require_clean_git

current="$(latest_version)"
if [[ -z "$current" ]]; then
  new_version="$FIRST_VERSION"
else
  new_version="$(next_version "$current" "$bump_type")"
fi
new_tag="v${new_version}"

if git rev-parse -q --verify "refs/tags/${new_tag}" >/dev/null; then
  echo "Tag ${new_tag} already exists." >&2
  exit 1
fi

git tag -a "$new_tag" -m "Release ${new_tag}"
echo "Created tag ${new_tag}"

if [[ "$push_tag" == "true" ]]; then
  git push origin "$new_tag"
  echo "Pushed ${new_tag} to origin (release workflow will run)"
fi
