#!/usr/bin/env sh
# ScalaSemantic MCP: launcher and installer in one script. SCALASEMANTIC_SELF_MARKER
#
#   curl -fsSL <raw-url> | sh                 # install for this user (all projects)
#   curl -fsSL <raw-url> | sh -s -- --project # install into the current project
#   scalasemantic-mcp serve .                 # run the server (what MCP clients invoke)
#
# It keeps the self-updating fat-jar cache and forwards everything else to the jar, which owns all
# install logic (client configs, scopes, SemanticDB setup, guard hook). See ADR-0004.
#
# Test/dev overrides: SCALASEMANTIC_JAR runs a local jar instead of a release;
# SCALASEMANTIC_SELF_SRC self-installs by copying that file instead of downloading.
set -eu

REPO="MercurieVV/ScalaSemantic"
DATA="${SCALASEMANTIC_HOME:-$HOME/.local/share/scalasemantic-mcp}"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
RAW_URL="https://raw.githubusercontent.com/$REPO/master/scripts/scalasemantic-mcp.sh"

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

# Piped through `sh`, $0 is "sh" and there is no file to exec: install a copy and hand over to it.
resolve_self() {
  [ -n "${0:-}" ] && [ -f "$0" ] || return 1
  grep -q SCALASEMANTIC_SELF_MARKER "$0" 2>/dev/null || return 1
  echo "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
}

self_install() {
  if [ "$1" = project ]; then dest="$(pwd)/scalasemantic-mcp.sh"
  else mkdir -p "$BIN_DIR"; dest="$BIN_DIR/scalasemantic-mcp"
  fi
  echo "scalasemantic-mcp: installing launcher to $dest" >&2
  if [ -n "${SCALASEMANTIC_SELF_SRC:-}" ] && [ -f "$SCALASEMANTIC_SELF_SRC" ]
  then cp "$SCALASEMANTIC_SELF_SRC" "$dest.tmp"
  else fetch_file "$RAW_URL" "$dest.tmp"
  fi
  mv -f "$dest.tmp" "$dest"
  chmod +x "$dest"
  echo "$dest"
}

newest_cached() { ls -t "$DATA"/scalasemantic-mcp-*.jar 2>/dev/null | head -1 || true; }

# The local development channel: a jar built by `./mill installLocal`. See ADR-0005.
newest_local() { ls -t "$DATA"/scalasemantic-mcp-*-local.jar 2>/dev/null | head -1 || true; }

resolve_tag() {
  if [ -n "${SCALASEMANTIC_VERSION:-}" ]; then printf '%s' "$SCALASEMANTIC_VERSION"; return 0; fi
  fetch_stdout "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
    | grep '"tag_name"' | head -1 | cut -d'"' -f4 || true
}

download_release() {
  jar="$DATA/scalasemantic-mcp-$1.jar"
  [ -f "$jar" ] && return 0
  echo "scalasemantic-mcp: downloading $1 ..." >&2
  fetch_file "https://github.com/$REPO/releases/download/$1/scalasemantic-mcp.jar" "$jar.tmp" || {
    rm -f "$jar.tmp"; return 1
  }
  mv -f "$jar.tmp" "$jar"
}

background_fetch() {
  lock="$DATA/.bgfetch.lock"
  if mkdir "$lock" 2>/dev/null; then
    trap 'rmdir "$lock" 2>/dev/null || true' EXIT INT TERM
    tag=$(resolve_tag); [ -n "$tag" ] && download_release "$tag" || true
  fi
}

jar_to_run() {
  if [ -n "${SCALASEMANTIC_JAR:-}" ]; then printf '%s' "$SCALASEMANTIC_JAR"; return 0; fi
  # A locally built jar owns the machine while it is installed: no release resolution, no
  # background fetch, so an auto-update cannot silently revert the developer to a release. It wins
  # regardless of mtime — a release downloaded later is still newer, and must not take the slot.
  # `scalasemantic-mcp --use-release` removes it. See ADR-0005.
  local_jar=$(newest_local)
  if [ -n "$local_jar" ]; then printf '%s' "$local_jar"; return 0; fi
  cached=$(newest_cached)
  if [ -z "${SCALASEMANTIC_VERSION:-}" ] && [ -n "$cached" ]; then
    ( "$SELF" --bg-fetch >/dev/null 2>&1 </dev/null & ) >/dev/null 2>&1 || true
    printf '%s' "$cached"; return 0
  fi
  tag=$(resolve_tag)
  [ -n "$tag" ] && download_release "$tag" || true
  jar="$DATA/scalasemantic-mcp-${tag:-unknown}.jar"
  if [ -f "$jar" ]; then printf '%s' "$jar"; return 0; fi
  cached=$(newest_cached)
  [ -n "$cached" ] || { echo "scalasemantic-mcp: no release and no cached jar" >&2; exit 1; }
  echo "scalasemantic-mcp: offline - using cached $(basename "$cached")" >&2
  printf '%s' "$cached"
}

mkdir -p "$DATA"

MODE=""
case "${1:-}" in
  --project) MODE=project; shift ;;
  --user)    MODE=user;    shift ;;
esac

# Bootstrap: nothing to exec. Install ourselves, then re-enter with an explicit mode, so the second
# pass always installs rather than falling through to serve.
if SELF=$(resolve_self); then :; else
  DEST=$(self_install "${MODE:-user}")
  exec "$DEST" "--${MODE:-user}" "$@"
fi

case "${1:-}" in
  --bg-fetch) background_fetch; exit 0 ;;
  --prefetch) shift; jar=$(jar_to_run)
              echo "scalasemantic-mcp: prefetched $(basename "$jar")" >&2; exit 0 ;;
  --use-release)
    removed=0
    for j in "$DATA"/*-local.jar; do
      [ -f "$j" ] || continue   # unmatched glob stays literal under sh; skip it
      rm -f "$j"
      echo "scalasemantic-mcp: removed $(basename "$j")" >&2
      removed=1
    done
    if [ "$removed" = 0 ]; then
      echo "scalasemantic-mcp: no local jar installed; already on the release channel" >&2
    else
      echo "scalasemantic-mcp: next start resolves a release" >&2
    fi
    exit 0 ;;
esac

JAR=$(jar_to_run)
SCALASEMANTIC_LAUNCHER="$SELF"
export SCALASEMANTIC_LAUNCHER

# The shell's --project is a valueless mode flag; the jar's --project takes a directory.
if [ "$MODE" = project ]; then
  exec java -jar "$JAR" install --scope project --project "$(pwd)" "$@"
elif [ "$MODE" = user ]; then
  exec java -jar "$JAR" install --scope user "$@"
fi

exec java -jar "$JAR" "$@"
