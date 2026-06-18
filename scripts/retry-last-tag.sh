#!/usr/bin/env bash

# Move the highest existing vX.Y.Z tag to HEAD, to retry a release that failed before the version
# was actually published.
#
# Usage:
#   scripts/retry-last-tag.sh [--push]
#
# Warning: never reuse a version that was already successfully published for different code.

set -euo pipefail

usage() { sed -n '3,10p' "$0" | sed 's/^# \{0,1\}//'; }

require_clean_git() {
  if [[ -n "$(git status --short)" ]]; then
    echo "Git worktree is not clean. Commit or stash changes before retagging." >&2
    exit 1
  fi
}

latest_tag() {
  git tag --list 'v*' | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; } | sort -V | tail -n 1
}

push_tag="false"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push) push_tag="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

require_clean_git

tag="$(latest_tag)"
[[ -z "$tag" ]] && { echo "No vX.Y.Z tag found." >&2; exit 1; }

old_commit="$(git rev-list -n 1 "$tag")"
new_commit="$(git rev-parse HEAD)"
if [[ "$old_commit" == "$new_commit" ]]; then
  echo "Tag ${tag} already points to HEAD (${new_commit})."
  exit 0
fi

git tag -d "$tag" >/dev/null
git tag -a "$tag" -m "Retry ${tag}" "$new_commit"
echo "Moved ${tag} from ${old_commit} to ${new_commit}"

if [[ "$push_tag" == "true" ]]; then
  git push origin ":refs/tags/${tag}"
  git push origin "$tag"
  echo "Force-updated ${tag} on origin"
fi
