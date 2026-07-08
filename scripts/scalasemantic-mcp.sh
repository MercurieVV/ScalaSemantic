#!/usr/bin/env sh
# Lightweight launcher for ScalaSemantic MCP.
#
# It only keeps the self-updating fat-jar cache, then forwards all user arguments to the jar.
# The jar owns setup/configuration logic (`setup`, client config edits, SemanticDB snippets, etc.).
set -eu

REPO="MercurieVV/ScalaSemantic"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"
SELF=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")

mkdir -p "$CACHE"

fetch_stdout() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
  else echo "scalasemantic-mcp: need curl or wget on PATH" >&2; return 1
  fi
}

fetch_file() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else echo "scalasemantic-mcp: need curl or wget on PATH" >&2; return 1
  fi
}

newest_cached() {
  ls -t "$CACHE"/scalasemantic-mcp-*.jar 2>/dev/null | head -1 || true
}

resolve_tag() {
  if [ -n "${SCALASEMANTIC_VERSION:-}" ]; then
    printf '%s' "$SCALASEMANTIC_VERSION"
    return 0
  fi
  fetch_stdout "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
    | grep '"tag_name"' | head -1 | cut -d'"' -f4 || true
}

download_release() {
  tag="$1"
  jar="$CACHE/scalasemantic-mcp-$tag.jar"
  [ -f "$jar" ] && return 0

  tmp="$jar.tmp"
  url="https://github.com/$REPO/releases/download/$tag/scalasemantic-mcp.jar"
  echo "scalasemantic-mcp: downloading $tag ..." >&2
  if fetch_file "$url" "$tmp"; then
    mv -f "$tmp" "$jar"
    return 0
  fi
  rm -f "$tmp"
  return 1
}

background_fetch() {
  lock="$CACHE/.bgfetch.lock"
  if mkdir "$lock" 2>/dev/null; then
    trap 'rmdir "$lock" 2>/dev/null || true' EXIT INT TERM
    tag=$(resolve_tag)
    [ -n "$tag" ] && download_release "$tag" || true
  fi
}

jar_to_run() {
  cached=$(newest_cached)
  if [ -z "${SCALASEMANTIC_VERSION:-}" ] && [ -n "$cached" ]; then
    ( "$SELF" --bg-fetch >/dev/null 2>&1 </dev/null & ) >/dev/null 2>&1 || true
    printf '%s' "$cached"
    return 0
  fi

  tag=$(resolve_tag)
  [ -n "$tag" ] && download_release "$tag" || true
  jar="$CACHE/scalasemantic-mcp-${tag:-unknown}.jar"
  if [ -f "$jar" ]; then
    printf '%s' "$jar"
    return 0
  fi

  cached=$(newest_cached)
  [ -n "$cached" ] || {
    echo "scalasemantic-mcp: cannot resolve a release and no cached jar found" >&2
    exit 1
  }
  echo "scalasemantic-mcp: offline - using cached $(basename "$cached")" >&2
  printf '%s' "$cached"
}

case "${1:-}" in
  --bg-fetch)
    background_fetch
    exit 0
    ;;
  --prefetch)
    shift
    jar=$(jar_to_run)
    echo "scalasemantic-mcp: prefetched $(basename "$jar")" >&2
    exit 0
    ;;
esac

JAR=$(jar_to_run)
SCALASEMANTIC_LAUNCHER="$SELF"
export SCALASEMANTIC_LAUNCHER
exec java -jar "$JAR" "$@"
