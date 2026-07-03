#!/usr/bin/env sh
# Default installer + launcher for the ScalaSemantic MCP server (Linux / macOS). Two subcommands:
#
#   scalasemantic-mcp.sh setup [--project DIR] [--client all|claude|codex|gemini|cline|roo|continue|antigravity]
#       Idempotently: enables SemanticDB in the target project's sbt build, writes/updates
#       SCALA_SEMANTIC_RULES.md + the per-client steering file (CLAUDE.md/AGENTS.md/.cursorrules/...),
#       prefetches (downloads+caches) the server jar, and merges an MCP server entry into each
#       client's config file (.mcp.json, .codex/config.toml, .gemini/settings.json, ...) — re-running
#       is safe, it only ever touches the "scala-semantic" entry.
#   scalasemantic-mcp.sh serve <semanticdb-root> [classpath]   (also the default with no subcommand)
#       Runs the server. Two paths, picked automatically:
#         1. if `cs` (coursier) is on PATH — resolve + cache the artifact from Maven Central and run
#            it (the JVM-native, npx-style way);
#         2. otherwise — download the fat jar from the latest GitHub Release once (cached by
#            version) and run it with `java -jar`.
# Either way, all progress goes to stderr so stdout carries only the JSON-RPC protocol stream.
#
# Cold-start strategy (jar path): once ANY version is cached, a launch serves the newest cached jar
# IMMEDIATELY and forks a detached background updater that fetches the latest release for the NEXT
# launch. So the download never races the client's connect timeout after the first time. The very
# first launch (empty cache) still blocks on the download — run `setup` (or plain `--prefetch`) once
# to warm the cache ahead of the first real connect.
#
#   --prefetch  Download + cache the artifact, then exit WITHOUT serving.
#   serve arguments are forwarded verbatim to the server: arg 1 = SemanticDB root, optional arg 2 =
#   the compile classpath (a path-separated string or a file containing one) that enables the
#   presentation-compiler backend for live overlay of uncompiled buffers.
#   --log / --log-output  Forwarded to the server to turn on its (off-by-default) file log:
#               --log writes a startup line + one line per tool call; --log-output additionally
#               logs each JSON-RPC response sent to the LLM. (Env equivalents: SCALASEMANTIC_LOG,
#               SCALASEMANTIC_LOG_OUTPUT; log file path via SCALASEMANTIC_LOG_FILE.)
# Requires: java on PATH (and optionally coursier) for `serve`. `setup`'s config merge additionally
# needs python3 on PATH (present by default on macOS and most Linux distros).
#
# Pin a version instead of "latest" by exporting SCALASEMANTIC_VERSION=v0.1.4 (pinned launches skip
# the background updater — you get exactly that version).
set -eu

REPO="MercurieVV/ScalaSemantic"
ORG="io.github.mercurievv"
ARTIFACT="scalasemantic-mcp_3"
MAIN="com.github.mercurievv.scalasemantic.mcpServer"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"
SELF=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")
SELF_DIR=$(dirname -- "$SELF")

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

serve_main() {
  # --prefetch: warm the cache and exit, never serve (decouples the download from the first connect).
  PREFETCH=0
  if [ "${1:-}" = "--prefetch" ]; then
    PREFETCH=1
    shift
  fi

  # --- path 1: coursier (preferred) — resolves transitive deps from Central, caches, runs --------
  if command -v cs >/dev/null 2>&1; then
    ver="${SCALASEMANTIC_VERSION:-latest.release}"
    ver="${ver#v}" # coursier wants the Maven version (0.1.4), not the git tag (v0.1.4)
    if [ "$PREFETCH" -eq 1 ]; then
      echo "scalasemantic-mcp: prefetching $ORG:$ARTIFACT:$ver via coursier" >&2
      cs fetch "$ORG:$ARTIFACT:$ver"
      return $?
    fi
    echo "scalasemantic-mcp: launching $ORG:$ARTIFACT:$ver via coursier" >&2
    exec cs launch "$ORG:$ARTIFACT:$ver" -M "$MAIN" -- "$@"
  fi

  # --- path 2: fat jar from GitHub Releases ------------------------------------------------------
  CACHED=$(newest_cached)

  if [ "$PREFETCH" -eq 0 ] && [ -z "${SCALASEMANTIC_VERSION:-}" ] && [ -n "$CACHED" ]; then
    # Cached jar present and not pinned: serve it NOW (zero download latency) and fork a detached
    # updater that pulls the latest release for the next launch. `( … & )` double-detaches so the
    # exec below does not wait on it; its fds are redirected away from the JSON-RPC stdout stream.
    JAR="$CACHED"
    ( "$SELF" --bg-fetch >/dev/null 2>&1 </dev/null & ) >/dev/null 2>&1 || true
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
    return 0
  fi

  exec java -jar "$JAR" "$@"
}

# ---------------------------------------------------------------------------------------------------
# setup: idempotently configure a project + its MCP clients to launch this script via `serve`.
# ---------------------------------------------------------------------------------------------------

ALL_CLIENTS="claude codex gemini cline roo continue antigravity"

target_for() {
  case "$1" in
    codex|openai|openai-codex) echo ".codex/config.toml toml" ;;
    claude|claude-code|anthropic) echo ".mcp.json json" ;;
    gemini|google|google-gemini|gemini-cli) echo ".gemini/settings.json json {\"timeout\":60000}" ;;
    antigravity|antigravity-cli|agy) echo ".agents/mcp_config.json json" ;;
    cline) echo ".cline/mcp.json json {\"disabled\":false,\"autoApprove\":[]}" ;;
    roo|roo-code) echo ".roo/mcp.json json {\"disabled\":false,\"alwaysAllow\":[],\"timeout\":60}" ;;
    continue|continue-dev) echo ".continue/config.yaml yaml" ;;
    generic|generic-json|json|oss|open-source|free) echo ".mcp.json json" ;;
    *) echo "" ;;
  esac
}

ensure_semanticdb_config() {
  local _project _file f
  _project="$1"
  for f in "$_project"/*.sbt; do
    [ -f "$f" ] || continue
    if grep -q semanticdbEnabled "$f" 2>/dev/null; then return 0; fi
  done
  if ! ls "$_project"/*.sbt >/dev/null 2>&1; then
    echo "scalasemantic-mcp: no .sbt files found in $_project; enable SemanticDB in your build tool before using ScalaSemantic" >&2
    return 0
  fi
  _file="$_project/scala-semantic.sbt"
  if [ ! -f "$_file" ]; then
    printf '// Generated by ScalaSemantic MCP setup.\nsemanticdbEnabled := true\n' > "$_file"
    echo "scalasemantic-mcp: created $_file" >&2
  fi
}

ensure_steer_file() {
  local _project _client _target _ref sed_bak
  _project="$1"; _client="$2"
  case "$_client" in
    claude|claude-code|anthropic) _target="$_project/CLAUDE.md"; _ref="[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)" ;;
    gemini|google|google-gemini|gemini-cli|antigravity|antigravity-cli|agy) _target="$_project/AGENTS.md"; _ref="@SCALA_SEMANTIC_RULES.md" ;;
    codex|openai|openai-codex) _target="$_project/.cursorrules"; _ref="[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)" ;;
    cline|roo|roo-code) _target="$_project/.clinerules"; _ref="[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)" ;;
    continue|continue-dev) _target="$_project/.continue/rules.txt"; _ref="SCALA_SEMANTIC_RULES.md" ;;
    *) return 0 ;;
  esac
  if [ -f "$_target" ]; then
    if grep -q "SCALA_CODE_RULES.md" "$_target" 2>/dev/null; then
      sed_bak=$(mktemp)
      sed 's/SCALA_CODE_RULES\.md/SCALA_SEMANTIC_RULES.md/g' "$_target" > "$sed_bak" && mv "$sed_bak" "$_target"
      echo "scalasemantic-mcp: updated $_target" >&2
    elif ! grep -q "SCALA_SEMANTIC_RULES.md" "$_target" 2>/dev/null; then
      printf '\n## Scala Code Rules\nPlease follow the rules in %s.\n' "$_ref" >> "$_target"
      echo "scalasemantic-mcp: updated $_target" >&2
    fi
  else
    mkdir -p "$(dirname "$_target")"
    if [ "$(basename "$_target")" = "AGENTS.md" ]; then
      printf '# AGENTS.md instructions\n\n<INSTRUCTIONS>\n%s\n</INSTRUCTIONS>\n' "$_ref" > "$_target"
    else
      printf '# Project Rules\n\nPlease follow the rules in %s for working with Scala code.\n' "$_ref" > "$_target"
    fi
    echo "scalasemantic-mcp: created $_target" >&2
  fi
}

ensure_rules() {
  local _project _client _rules c
  _project="$1"; _client="$2"
  _rules="$_project/SCALA_SEMANTIC_RULES.md"
  if [ ! -f "$_rules" ]; then
    cat > "$_rules" <<'EOF'
# Scala Semantic Rules

For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.

Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type, signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are available.

Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.
EOF
    echo "scalasemantic-mcp: created $_rules" >&2
  fi
  if [ "$_client" = "all" ]; then
    for c in $ALL_CLIENTS; do ensure_steer_file "$_project" "$c"; done
  else
    ensure_steer_file "$_project" "$_client"
  fi
}

write_client_configs() {
  local _project _client _command _cpfile _merge _clients c _t _relpath _fmt _extra _argv
  _project="$1"; _client="$2"; _command="$3"; _cpfile="$4"
  if ! command -v python3 >/dev/null 2>&1; then
    echo "scalasemantic-mcp: python3 not found on PATH — skipping MCP client config merge." >&2
    echo "scalasemantic-mcp: add this server manually, pointing args at: $_command serve $_project $_cpfile" >&2
    return 0
  fi
  _merge="$SELF_DIR/lib/mcp-config-merge.py"
  if [ "$_client" = "all" ]; then _clients="$ALL_CLIENTS"; else _clients="$_client"; fi
  for c in $_clients; do
    _t=$(target_for "$c")
    [ -n "$_t" ] || { echo "scalasemantic-mcp: unsupported client '$c'" >&2; continue; }
    _relpath=$(echo "$_t" | cut -d' ' -f1)
    _fmt=$(echo "$_t" | cut -d' ' -f2)
    _extra=$(echo "$_t" | cut -d' ' -f3-)
    if [ -z "$_extra" ] || [ "$_extra" = "$_fmt" ]; then _extra="{}"; fi
    _argv=$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "$_command" serve "$_project" "$_cpfile")
    python3 "$_merge" "$_fmt" "$_project/$_relpath" "scala-semantic" "$_argv" "$_extra"
  done
}

setup_main() {
  local _project _client _command _skip_semanticdb _cpfile
  _project=$(CDPATH= cd -- "." && pwd)
  _client="all"
  _command="$SELF"
  _skip_semanticdb=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --project|--root) _project=$(mkdir -p "$2" && CDPATH= cd -- "$2" && pwd); shift 2 ;;
      --client|-c) _client="$2"; shift 2 ;;
      --command) _command="$2"; shift 2 ;;
      --skip-semanticdb-config) _skip_semanticdb=1; shift ;;
      --help|-h) usage; exit 0 ;;
      *) echo "scalasemantic-mcp: unknown setup argument: $1" >&2; usage; exit 2 ;;
    esac
  done

  [ "$_skip_semanticdb" -eq 1 ] || ensure_semanticdb_config "$_project"
  ensure_rules "$_project" "$_client"

  echo "scalasemantic-mcp: prefetching the server jar ..." >&2
  serve_main --prefetch "$_project" || echo "scalasemantic-mcp: prefetch skipped (will download on first serve)" >&2

  _cpfile="$CACHE/scalasemantic-mcp-classpath.txt"
  [ -f "$_cpfile" ] || : > "$_cpfile"

  write_client_configs "$_project" "$_client" "$_command" "$_cpfile"
}

usage() {
  cat >&2 <<EOF
Usage:
  scalasemantic-mcp.sh setup [--client all|claude|codex|gemini|cline|roo|continue|antigravity] [--project DIR]
  scalasemantic-mcp.sh serve <semanticdb-root> [classpath-file] [--log] [--log-output]

setup writes MCP client config that launches this same script:
  command = $SELF
  args    = [serve, <project>, <classpath-file>]
EOF
}

case "${1:-}" in
  setup|configure|install) shift; setup_main "$@" ;;
  serve|run) shift; serve_main "$@" ;;
  --help|-h|help) usage; exit 0 ;;
  *) serve_main "$@" ;;
esac