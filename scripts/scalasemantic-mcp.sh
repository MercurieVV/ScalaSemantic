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
# Cold-start strategy (jar path): once ANY version is cached, a launch serves the newest cached jar
# IMMEDIATELY and forks a detached background updater that fetches the latest release for the NEXT
# launch. So the download never races the client's connect timeout after the first time. The very
# first launch (empty cache) still blocks on the download — run `--prefetch` once, or `sbt
# mcpClientConfig` (which prefetches for you), to warm the cache ahead of the first real connect.
#
# Usage:   scalasemantic-mcp.sh [--prefetch] <semanticdb-root> [classpath]   (root defaults to ".")
#   --prefetch  Download + cache the artifact, then exit WITHOUT serving. Run this once before
#               adding the server to your MCP config so the first real connect hits a warm cache
#               instead of racing the client's connection timeout while an ~88 MB jar downloads.
#   All other arguments are forwarded verbatim to the server: arg 1 = SemanticDB root, optional
#   arg 2 = the compile classpath (a path-separated string or a file containing one) that enables
#   the presentation-compiler backend for live overlay of uncompiled buffers.
#   --log / --log-output  Forwarded to the server to turn on its (off-by-default) file log:
#               --log writes a startup line + one line per tool call; --log-output additionally
#               logs each JSON-RPC response sent to the LLM. (Env equivalents: SCALASEMANTIC_LOG,
#               SCALASEMANTIC_LOG_OUTPUT; log file path via SCALASEMANTIC_LOG_FILE.)
# Requires: java on PATH (and optionally coursier). No sbt needed.
#
# Pin a version instead of "latest" by exporting SCALASEMANTIC_VERSION=v0.1.4 (pinned launches skip
# the background updater — you get exactly that version).
set -eu

REPO="MercurieVV/ScalaSemantic"
ORG="io.github.mercurievv"
ARTIFACT="scalasemantic-mcp_3"
MAIN="com.github.mercurievv.scalasemantic.mcpServer"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"

mkdir -p "$CACHE"

# fetch URL to stdout (used for the GitHub API call). Status noise stays on stderr.
fetch_stdout() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
  else echo "scalasemantic-mcp: need coursier, or curl/wget, on PATH" >&2; return 1
  fi
}

# Resumable download of $1 to file $2. curl `-C -` / wget `-c` continue a partial `.tmp` from a
# previous (e.g. timed-out) attempt instead of restarting, so a slow first download completes
# across the client's auto-reconnect retries. Returns the tool's exit status.
fetch_file() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -C - "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then wget -q -c -O "$2" "$1"
  else echo "scalasemantic-mcp: need coursier, or curl/wget, on PATH" >&2; return 1
  fi
}

# newest cached fat jar (most recently modified), or empty if none.
newest_cached() { ls -t "$CACHE"/scalasemantic-mcp-*.jar 2>/dev/null | head -1 || true; }

# resolve the release tag to use: explicit pin, else the latest release's tag_name (may be empty).
resolve_tag() {
  if [ -n "${SCALASEMANTIC_VERSION:-}" ]; then printf '%s' "$SCALASEMANTIC_VERSION"; return 0; fi
  fetch_stdout "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
    | grep '"tag_name"' | head -1 | cut -d'"' -f4 || true
}

# Download release $1's fat jar into the cache if not already present. Resumable + atomic: writes a
# `.tmp` and renames only on a verified-complete download. Returns 0 on success (or already cached).
download_release() {
  _tag="$1"
  _jar="$CACHE/scalasemantic-mcp-$_tag.jar"
  [ -f "$_jar" ] && return 0
  _url="https://github.com/$REPO/releases/download/$_tag/scalasemantic-mcp.jar"
  echo "scalasemantic-mcp: downloading $_tag ..." >&2
  set +e
  fetch_file "$_url" "$_jar.tmp"
  rc=$?
  set -e
  if [ "$rc" -ne 0 ] && [ -f "$_jar.tmp" ]; then
    # A resume can fail with HTTP 416 when the .tmp is in fact already complete. Confirm by
    # comparing sizes against the server's Content-Length; if they match, treat it as done.
    remote=$(curl -fsSIL "$_url" 2>/dev/null | awk 'tolower($1) ~ /^content-length:/ {print $2}' | tr -d '\r' | tail -1)
    local=$(wc -c < "$_jar.tmp" 2>/dev/null | tr -d ' ')
    if [ -n "$remote" ] && [ "$remote" = "$local" ]; then rc=0; fi
  fi
  if [ "$rc" -eq 0 ]; then
    mv -f "$_jar.tmp" "$_jar"
    return 0
  fi
  # Keep the partial `.tmp` so the next attempt resumes it.
  echo "scalasemantic-mcp: download incomplete (rc=$rc); will resume on next launch" >&2
  return 1
}

# --bg-fetch: internal mode forked (detached) by a cached-serve launch to fetch the latest release
# for the NEXT launch, then exit. A mkdir-based lock keeps concurrent launches from racing the same
# `.tmp`. Never serves, never touches stdout.
if [ "${1:-}" = "--bg-fetch" ]; then
  _lock="$CACHE/.bgfetch.lock"
  if mkdir "$_lock" 2>/dev/null; then
    trap 'rmdir "$_lock" 2>/dev/null || true' EXIT INT TERM
    _tag=$(resolve_tag)
    [ -n "$_tag" ] && download_release "$_tag" || true
  fi
  exit 0
fi

# --prefetch: warm the cache and exit, never serve (decouples the download from the first connect).
PREFETCH=0
if [ "${1:-}" = "--prefetch" ]; then
  PREFETCH=1
  shift
fi

# --- path 1: coursier (preferred) — resolves transitive deps from Central, caches, runs ----------
if command -v cs >/dev/null 2>&1; then
  ver="${SCALASEMANTIC_VERSION:-latest.release}"
  ver="${ver#v}" # coursier wants the Maven version (0.1.4), not the git tag (v0.1.4)
  if [ "$PREFETCH" -eq 1 ]; then
    echo "scalasemantic-mcp: prefetching $ORG:$ARTIFACT:$ver via coursier" >&2
    exec cs fetch "$ORG:$ARTIFACT:$ver"
  fi
  echo "scalasemantic-mcp: launching $ORG:$ARTIFACT:$ver via coursier" >&2
  exec cs launch "$ORG:$ARTIFACT:$ver" -M "$MAIN" -- "$@"
fi

# --- path 2: fat jar from GitHub Releases --------------------------------------------------------
CACHED=$(newest_cached)

if [ "$PREFETCH" -eq 0 ] && [ -z "${SCALASEMANTIC_VERSION:-}" ] && [ -n "$CACHED" ]; then
  # Cached jar present and not pinned: serve it NOW (zero download latency) and fork a detached
  # updater that pulls the latest release for the next launch. `( … & )` double-detaches so the
  # exec below does not wait on it; its fds are redirected away from the JSON-RPC stdout stream.
  JAR="$CACHED"
  ( "$0" --bg-fetch >/dev/null 2>&1 </dev/null & ) >/dev/null 2>&1 || true
else
  # No cache (must block once), or pinned (fetch exactly that version), or prefetch (warm fully).
  TAG=$(resolve_tag)
  if [ -n "$TAG" ]; then download_release "$TAG" || true; fi
  JAR="$CACHE/scalasemantic-mcp-${TAG:-unknown}.jar"
  if [ ! -f "$JAR" ]; then
    JAR=$(newest_cached)
    [ -n "$JAR" ] || { echo "scalasemantic-mcp: cannot resolve a release and no cached jar found" >&2; exit 1; }
    echo "scalasemantic-mcp: offline — using cached $(basename "$JAR")" >&2
  fi
fi

if [ "$PREFETCH" -eq 1 ]; then
  echo "scalasemantic-mcp: prefetched $(basename "$JAR")" >&2
  exit 0
fi

exec java -jar "$JAR" "$@"