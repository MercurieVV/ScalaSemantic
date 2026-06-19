#!/usr/bin/env sh
# Auto-download + run the ScalaSemantic MCP server (Linux / macOS).
#
# Two paths, picked automatically:
#   1. if `cs` (coursier) is on PATH — resolve + cache the artifact from Maven Central and run it
#      (the JVM-native, npx-style way);
#   2. otherwise — download the fat jar from the latest GitHub Release once (cached by version) and
#      run it with `java -jar`.
# Either way, all progress goes to stderr so stdout carries only the JSON-RPC protocol stream.
#
# Usage:   scalasemantic-mcp.sh <semanticdb-root> [classpath]   (root defaults to ".")
#   All arguments are forwarded verbatim to the server: arg 1 = SemanticDB root, optional arg 2 =
#   the compile classpath (a path-separated string or a file containing one) that enables the
#   presentation-compiler backend for live overlay of uncompiled buffers.
# Requires: java on PATH (and optionally coursier). No sbt needed.
#
# Pin a version instead of "latest" by exporting SCALASEMANTIC_VERSION=v0.1.4
set -eu

REPO="MercurieVV/ScalaSemantic"
ORG="io.github.mercurievv"
ARTIFACT="scalasemantic-mcp_3"
MAIN="com.github.mercurievv.scalasemantic.mcpServer"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"
ROOT="${1:-.}"

# --- path 1: coursier (preferred) — resolves transitive deps from Central, caches, runs ----------
if command -v cs >/dev/null 2>&1; then
  ver="${SCALASEMANTIC_VERSION:-latest.release}"
  ver="${ver#v}" # coursier wants the Maven version (0.1.4), not the git tag (v0.1.4)
  echo "scalasemantic-mcp: launching $ORG:$ARTIFACT:$ver via coursier" >&2
  exec cs launch "$ORG:$ARTIFACT:$ver" -M "$MAIN" -- "$@"
fi

# --- path 2: fat jar from GitHub Releases --------------------------------------------------------
mkdir -p "$CACHE"

# tiny fetch helper: $1=url $2=outfile ("-" = stdout). Status noise stays on stderr.
fetch() {
  if command -v curl >/dev/null 2>&1; then
    if [ "$2" = "-" ]; then curl -fsSL "$1"; else curl -fsSL "$1" -o "$2"; fi
  elif command -v wget >/dev/null 2>&1; then
    if [ "$2" = "-" ]; then wget -qO- "$1"; else wget -qO "$2" "$1"; fi
  else
    echo "scalasemantic-mcp: need coursier, or curl/wget, on PATH" >&2; exit 1
  fi
}

# resolve version: explicit pin, else the latest release's tag_name
TAG="${SCALASEMANTIC_VERSION:-}"
if [ -z "$TAG" ]; then
  TAG=$(fetch "https://api.github.com/repos/$REPO/releases/latest" - 2>/dev/null \
        | grep '"tag_name"' | head -1 | cut -d'"' -f4 || true)
fi

JAR="$CACHE/scalasemantic-mcp-${TAG:-unknown}.jar"

# download if missing; fall back to newest cached jar when offline
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

exec java -jar "$JAR" "$@"