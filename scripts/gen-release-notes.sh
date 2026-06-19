#!/usr/bin/env bash

# Render docs/RELEASE_NOTES.md from the git tags, newest first, using changelog.sh as the single
# renderer — so the site page shows exactly what each GitHub Release body shows (user-facing
# Conventional-Commit changes only; docs/refactor/test/chore omitted).
#
# This file is GENERATED — do not hand-edit. The docs-site CI job runs it before building the site,
# so a protected master needs no commit. Run it locally before `sbt docs/run` to preview.
#
# Usage: scripts/gen-release-notes.sh [output-file]   (default docs/RELEASE_NOTES.md)

set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" # repo root, so paths are stable

here="$(dirname "${BASH_SOURCE[0]}")"
out="${1:-docs/RELEASE_NOTES.md}"

# Semver tags, highest first.
tags=$(git tag --list 'v*' | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; } | sort -V -r)

{
  echo "# Release notes"
  echo
  echo "User-facing changes per release, newest first. Generated from the release tags (only"
  echo "\`feat\`/\`fix\`/\`perf\` and breaking changes are listed; docs, refactors, tests and chores are"
  echo "omitted). The release *process* is in [Releasing](RELEASING.md); do not hand-edit this page."
  echo

  prev=""
  # `sort -V -r` gives newest-first for printing; pair each tag with the one below it for the range.
  for tag in $tags; do
    date=$(git log -1 --format=%ad --date=short "$tag")
    echo "## $tag — $date"
    echo
    # Range is previous-lower-tag..tag; the lowest tag has no predecessor (all history up to it).
    lower=$(git tag --list 'v*' | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; } | sort -V | \
            awk -v t="$tag" '$0==t{print prev; exit} {prev=$0}')
    range="${lower:+$lower..}$tag"
    bash "$here/changelog.sh" "$range"
    echo
    prev="$tag"
  done
} > "$out"

echo "Wrote $out"
