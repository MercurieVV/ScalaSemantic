#!/usr/bin/env sh
# Auto-download + run the latest ScalaSemantic MCP server (Linux / macOS).
#
# Resolves the latest GitHub Release, downloads its fat jar once (cached by version), then runs it
# with `java -jar`. All progress goes to stderr so stdout carries only the JSON-RPC protocol stream.
#
# Usage:   scalasemantic-mcp.sh <semanticdb-root>     (root defaults to ".")
# Requires: java on PATH, plus curl or wget. No coursier/sbt needed.
#
# Pin a version instead of "latest" by exporting SCALASEMANTIC_VERSION=v0.1.4
set -eu

REPO="MercurieVV/ScalaSemantic"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"
mkdir -p "$CACHE"

# --- tiny fetch helper: $1=url $2=outfile ("-" = stdout). All status noise stays on stderr. -------
fetch() {
  if command -v curl >/dev/null 2>&1; then
    if [ "$2" = "-" ]; then curl -fsSL "$1"; else curl -fsSL "$1" -o "$2"; fi
  elif command -v wget >/dev/null 2>&1; then
    if [ "$2" = "-" ]; then wget -qO- "$1"; else wget -qO "$2" "$1"; fi
  else
    echo "scalasemantic-mcp: need curl or wget on PATH" >&2; exit 1
  fi
}

# --- resolve version: explicit pin, else the latest release's tag_name --------------------------
TAG="${SCALASEMANTIC_VERSION:-}"
if [ -z "$TAG" ]; then
  TAG=$(fetch "https://api.github.com/repos/$REPO/releases/latest" - 2>/dev/null \
        | grep '"tag_name"' | head -1 | cut -d'"' -f4 || true)
fi

JAR="$CACHE/scalasemantic-mcp-${TAG:-unknown}.jar"

# --- download if missing; fall back to newest cached jar when offline ---------------------------
if [ -n "$TAG" ] && [ ! -f "$JAR" ]; then
  URL="https://github.com/$REPO/releases/download/$TAG/scalasemantic-mcp.jar"
  echo "scalasemantic-mcp: downloading $TAG ..." >&2
  fetch "$URL" "$JAR.tmp"
  mv "$JAR.tmp" "$JAR"
fi
if [ ! -f "$JAR" ]; then
  JAR=$(ls -t "$CACHE"/scalasemantic-mcp-*.jar 2>/dev/null | head -1 || true)
  [ -n "$JAR" ] || { echo "scalasemantic-mcp: cannot resolve a release and no cached jar found" >&2; exit 1; }
  echo "scalasemantic-mcp: offline — using cached $(basename "$JAR")" >&2
fi

ROOT="${1:-.}"
exec java -jar "$JAR" "$ROOT"