#!/usr/bin/env bash

# Update documentation snippets that must show the latest published artifact version.
#
# Usage:
#   scripts/update-doc-versions.sh 0.1.4
#   scripts/update-doc-versions.sh v0.1.4
#
# GitHub Actions passes the just-published tag version after `sbt ci-release` succeeds. Keeping this
# as an explicit step avoids making normal docs rendering depend on network access or Maven Central
# availability.

set -euo pipefail

if [[ $# -ne 1 || -z "${1:-}" ]]; then
  echo "Usage: $0 VERSION_OR_TAG" >&2
  exit 2
fi

version="${1#v}"
tag="v$version"

case "$version" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *)
    echo "Expected a release version like 0.1.4 or v0.1.4, got: $1" >&2
    exit 2
    ;;
esac

perl -0pi -e '
  s/addSbtPlugin\("io\.github\.mercurievv" % "sbt-scalasemantic-mcp" % "[^"]+"\)/addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "'"$version"'")/g;
  s/SCALASEMANTIC_VERSION=v[0-9]+\.[0-9]+\.[0-9]+/SCALASEMANTIC_VERSION='"$tag"'/g;
' README.md docs/INTEGRATION.md

echo "Updated docs to ScalaSemantic $version"
