#!/usr/bin/env sh
# Default installer + launcher for the ScalaSemantic MCP server (Linux / macOS). Two subcommands:
#
#   scalasemantic-mcp.sh setup [--project DIR] [--client all|claude|codex|gemini|cline|roo|continue|antigravity]
#       Idempotently: detects the build tool (sbt/Mill/Gradle/scala-cli) and enables SemanticDB where
#       it can be done safely (sbt, scala-cli) or prints the snippet to add by hand (Mill, Gradle),
#       writes/updates
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
#   the compile classpath metadata file (normally `.scala-semantic/classpath-<tool>.json`) that
#   enables the presentation-compiler backend for live overlay of uncompiled buffers.
#   --log / --log-output  Forwarded to the server to turn on its (off-by-default) file log:
#               --log writes a startup line + one line per tool call; --log-output additionally
#               logs each JSON-RPC response sent to the LLM. (Env equivalents: SCALASEMANTIC_LOG,
#               SCALASEMANTIC_LOG_OUTPUT; log file path via SCALASEMANTIC_LOG_FILE.)
# Requires: java on PATH (and optionally coursier) for `serve`, and standard POSIX tools (awk, sed,
# grep) for `setup`'s config merge — no python3, no jq, no extra interpreter to install.
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
    gemini|google|google-gemini|gemini-cli) echo ".gemini/settings.json json" ;;
    antigravity|antigravity-cli|agy) echo ".agents/mcp_config.json json" ;;
    cline) echo ".cline/mcp.json json" ;;
    roo|roo-code) echo ".roo/mcp.json json" ;;
    continue|continue-dev) echo ".continue/config.yaml yaml" ;;
    generic|generic-json|json|oss|open-source|free) echo ".mcp.json json" ;;
    *) echo "" ;;
  esac
}

# Extra JSON object fields some clients want on their server entry, rendered as raw ", key: value"
# fragments appended right before the entry's closing brace (empty string = no extras).
extra_json_fields() {
  case "$1" in
    gemini|google|google-gemini|gemini-cli) printf ',\n      "timeout": 60000' ;;
    cline) printf ',\n      "disabled": false,\n      "autoApprove": []' ;;
    roo|roo-code) printf ',\n      "disabled": false,\n      "alwaysAllow": [],\n      "timeout": 60' ;;
    *) printf '' ;;
  esac
}

# Escapes backslash + double-quote so a raw string can be embedded in a JSON/TOML string literal.
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# Detects the project's build tool and dispatches to a tool-specific enabler. sbt and scala-cli get
# a real auto-enable via a brand-new standalone config file (sbt merges every *.sbt in project root;
# scala-cli merges every `//> using` directive from every file, including a dedicated project.scala
# — neither can collide with or corrupt hand-written build code). Mill/Gradle builds have no such
# auto-merged file: enabling SemanticDB means editing the EXISTING build.mill/build.sc/
# build.gradle(.kts), which this script does not parse, so doing that in place risks corrupting
# hand-written module code — instead we detect + print the exact snippet to add (same as
# docs/getting-started/integration.md), and leave the file untouched.
#
# build.mill (Mill's own build file) is checked before the scala-cli fallback so a Mill project's
# build.mill is never mistaken for a loose scala-cli script.
ensure_semanticdb_config() {
  local _project
  _project="$1"
  if ls "$_project"/*.sbt >/dev/null 2>&1; then
    ensure_semanticdb_sbt "$_project"
  elif [ -f "$_project/build.mill" ] || [ -f "$_project/build.sc" ]; then
    ensure_semanticdb_mill "$_project"
  elif [ -f "$_project/build.gradle" ] || [ -f "$_project/build.gradle.kts" ]; then
    ensure_semanticdb_gradle "$_project"
  elif [ -f "$_project/project.scala" ] || ls "$_project"/*.scala >/dev/null 2>&1 || ls "$_project"/*.sc >/dev/null 2>&1; then
    ensure_semanticdb_scalacli "$_project"
  else
    echo "scalasemantic-mcp: no sbt/Mill/Gradle/scala-cli build files found in $_project; enable SemanticDB manually (see docs/getting-started/integration.md) before using ScalaSemantic" >&2
  fi
}

ensure_semanticdb_sbt() {
  local _project _file f has_semanticdb has_cp_writer
  _project="$1"
  has_semanticdb=0
  has_cp_writer=0
  for f in "$_project"/*.sbt; do
    [ -f "$f" ] || continue
    grep -q semanticdbEnabled "$f" 2>/dev/null && has_semanticdb=1
    grep -q scalaSemanticWriteClasspath "$f" 2>/dev/null && has_cp_writer=1
  done
  _file="$_project/scala-semantic.sbt"
  if [ "$has_cp_writer" -eq 0 ]; then
    cat > "$_file" <<'EOF'
// Generated by ScalaSemantic MCP setup.
import sbt._
import Keys._
import java.nio.file.{Files, StandardCopyOption}

ThisBuild / semanticdbEnabled := true

lazy val scalaSemanticWriteClasspath =
  taskKey[File]("Write module-aware compile classpath metadata for ScalaSemantic MCP.")

def scalaSemanticJsonString(value: String): String =
  "\"" + value.flatMap {
    case '"'  => "\\\""
    case '\\' => "\\\\"
    case '\b' => "\\b"
    case '\f' => "\\f"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c if c < ' ' => "\\u%04x".format(c.toInt)
    case c => c.toString
  } + "\""

def scalaSemanticRel(root: File, file: File): String = {
  val rootPath = root.toPath.toAbsolutePath.normalize()
  val path = file.toPath.toAbsolutePath.normalize()
  if (path.startsWith(rootPath)) rootPath.relativize(path).toString else path.toString
}

ThisBuild / scalaSemanticWriteClasspath := {
  val root = (ThisBuild / baseDirectory).value
  val ids = name.all(ScopeFilter(inAnyProject)).value
  val dirs = baseDirectory.all(ScopeFilter(inAnyProject)).value
  val versions = scalaVersion.all(ScopeFilter(inAnyProject)).value
  val cps = (Compile / fullClasspath).all(ScopeFilter(inAnyProject)).value
  val modules = ids.zip(dirs).zip(versions).zip(cps).map {
    case (((id, dir), version), cp) =>
      val classpath = cp.map(_.data).distinct.map { entry =>
        "        " + scalaSemanticJsonString(scalaSemanticRel(root, entry))
      }.mkString(",\n")
      s"""    {
         |      "id": ${scalaSemanticJsonString(id)},
         |      "baseDir": ${scalaSemanticJsonString(scalaSemanticRel(root, dir))},
         |      "scalaVersion": ${scalaSemanticJsonString(version)},
         |      "configuration": "Compile",
         |      "classpath": [
         |$classpath
         |      ]
         |    }""".stripMargin
  }.mkString(",\n")
  val content =
    s"""{
       |  "schemaVersion": 1,
       |  "buildTool": "sbt",
       |  "modules": [
       |$modules
       |  ]
       |}
       |""".stripMargin
  val out = root / ".scala-semantic" / "classpath-sbt.json"
  IO.createDirectory(out.getParentFile)
  val current = if (out.isFile) IO.read(out) else ""
  if (current != content) {
    val tmp = out.getParentFile / (out.getName + ".tmp")
    IO.write(tmp, content)
    Files.move(tmp.toPath, out.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }
  out
}

EOF
    echo "scalasemantic-mcp: created $_file" >&2
  elif [ "$has_semanticdb" -eq 0 ]; then
    printf '\n// Generated by ScalaSemantic MCP setup.\nThisBuild / semanticdbEnabled := true\n' >> "$_file"
    echo "scalasemantic-mcp: updated $_file" >&2
  fi
}

ensure_semanticdb_mill() {
  local _project f
  _project="$1"
  for f in "$_project/build.mill" "$_project/build.sc"; do
    [ -f "$f" ] || continue
    if grep -q 'Xsemanticdb' "$f" 2>/dev/null; then return 0; fi
  done
  cat >&2 <<'EOF'
scalasemantic-mcp: Mill project detected, SemanticDB not enabled. NOTE Mill's `semanticDbEnabled`
only feeds the on-demand `semanticDbData` target — a plain `mill __.compile` emits NO *.semanticdb,
so that flag alone leaves ScalaSemantic with an empty index. Instead make the normal compile emit it
via the compiler flag. Not auto-editing build.mill/build.sc (risk of corrupting hand-written module
code) — add this to each ScalaModule (`-sourceroot` must be the BUILD ROOT, not the module dir, so
multi-module source paths stay unique):

    def scalacOptions = super.scalacOptions() ++
      Seq("-Xsemanticdb", "-sourceroot", build.moduleDir.toString) // build.sc: build.millSourcePath

For live-buffer typechecking, add a build task that writes `.scala-semantic/classpath-mill.json`
from each Scala module's compile classpath. ScalaSemantic's own `build.mill` contains a compact
`scalaSemanticWriteClasspath` example. Then run it when module dependencies or build config change.
EOF
}

ensure_semanticdb_gradle() {
  local _project f
  _project="$1"
  for f in "$_project/build.gradle" "$_project/build.gradle.kts"; do
    [ -f "$f" ] || continue
    if grep -Eq 'semanticdb|Ysemanticdb' "$f" 2>/dev/null; then return 0; fi
  done
  cat >&2 <<'EOF'
scalasemantic-mcp: Gradle project detected, SemanticDB not enabled. Not auto-editing
build.gradle(.kts) (risk of corrupting hand-written build logic) — add to the Scala compile task.
Scala 3:

    tasks.withType(ScalaCompile) {
      scalaCompileOptions.additionalParameters = ["-Ysemanticdb", "-sourceroot", projectDir.toString()]
    }

Scala 2.13 needs the semanticdb-scalac compiler plugin instead (see
docs/getting-started/integration.md for the exact dependency + flags). Then recompile.
EOF
}

ensure_semanticdb_scalacli() {
  local _project _file f is213
  _project="$1"
  for f in "$_project"/project.scala "$_project"/*.sc "$_project"/*.scala; do
    [ -f "$f" ] || continue
    if grep -Eq 'semanticdb|Ysemanticdb' "$f" 2>/dev/null; then return 0; fi
  done
  _file="$_project/project.scala"
  if [ ! -f "$_file" ]; then
    is213=0
    for f in "$_project"/*.sc "$_project"/*.scala; do
      [ -f "$f" ] || continue
      grep -Eq 'using scala "2\.' "$f" 2>/dev/null && is213=1
    done
    if [ "$is213" -eq 1 ]; then
      printf '// Generated by ScalaSemantic MCP setup.\n//> using plugin "org.scalameta:::semanticdb-scalac:4.13.9"\n//> using options "-Yrangepos" "-P:semanticdb:sourceroot:."\n' > "$_file"
    else
      printf '// Generated by ScalaSemantic MCP setup.\n//> using options "-Ysemanticdb" "-sourceroot" "."\n' > "$_file"
    fi
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

# Writes the three merge awk programs (embedded below, not read from a sibling file — this script
# is distributed and run standalone via `curl -o scalasemantic-mcp.sh`, with no lib/ next to it) out
# to the cache dir so `awk -f` has a real file to load. Cheap; rewritten on every setup run so an
# updated script always uses its own matching copy, never a stale one from a previous version.
write_awk_libs() {
  mkdir -p "$CACHE/lib"

  cat > "$CACHE/lib/json-merge.awk" <<'AWK_EOF'
BEGIN {
  server = ENVIRON["MCPM_SERVER"]
  entry = ENVIRON["MCPM_ENTRY"]
}

function indexOfCharFrom(s, from, ch,    i, n) {
  n = length(s)
  for (i = from; i <= n; i++) if (substr(s, i, 1) == ch) return i
  return -1
}

function skipWs(s, i, limit,    c) {
  while (i <= limit) {
    c = substr(s, i, 1)
    if (c == " " || c == "\n" || c == "\t" || c == "\r") i++
    else break
  }
  return i
}

function trimStr(s,   t) {
  t = s
  gsub(/^[ \t\r\n]+/, "", t)
  gsub(/[ \t\r\n]+$/, "", t)
  return t
}

function matchBracket(s, openIdx,    i, n, depth, inStr, esc, c) {
  n = length(s)
  depth = 0; inStr = 0; esc = 0
  for (i = openIdx; i <= n; i++) {
    c = substr(s, i, 1)
    if (inStr) {
      if (esc) { esc = 0 }
      else if (c == "\\") { esc = 1 }
      else if (c == "\"") { inStr = 0 }
    } else {
      if (c == "\"") inStr = 1
      else if (c == "{" || c == "[") depth++
      else if (c == "}" || c == "]") { depth--; if (depth == 0) return i }
    }
  }
  return -1
}

function findKey(s, start, end, key,    target, tlen, i, depth, inStr, esc, c, j) {
  target = "\"" key "\""
  tlen = length(target)
  depth = 0; inStr = 0; esc = 0
  for (i = start; i <= end; i++) {
    c = substr(s, i, 1)
    if (inStr) {
      if (esc) { esc = 0 }
      else if (c == "\\") { esc = 1 }
      else if (c == "\"") { inStr = 0 }
    } else {
      if (c == "\"") {
        if (depth == 0 && substr(s, i, tlen) == target) {
          j = skipWs(s, i + tlen, end)
          if (substr(s, j, 1) == ":") return i
        }
        inStr = 1
      } else if (c == "{" || c == "[") depth++
      else if (c == "}" || c == "]") depth--
    }
  }
  return -1
}

{ content = content $0 "\n" }

END {
  if (trimStr(content) == "") {
    printf "{\n  \"mcpServers\": {\n    \"%s\": %s\n  }\n}\n", server, entry
    exit
  }

  rootOpen = index(content, "{")
  if (rootOpen == 0) { printf "%s", content; exit }
  rootClose = matchBracket(content, rootOpen)
  if (rootClose < 0) { printf "%s", content; exit }

  msKey = findKey(content, rootOpen + 1, rootClose - 1, "mcpServers")
  if (msKey < 0) {
    hadEntries = (trimStr(substr(content, rootOpen + 1, rootClose - rootOpen - 1)) != "")
    block = "\n  \"mcpServers\": {\n    \"" server "\": " entry "\n  }"
    comma = hadEntries ? "," : ""
    printf "%s%s%s%s", substr(content, 1, rootOpen), block, comma, substr(content, rootOpen + 1)
    exit
  }

  colonPos = indexOfCharFrom(content, msKey, ":")
  objOpen = indexOfCharFrom(content, colonPos, "{")
  objClose = matchBracket(content, objOpen)

  # The existing value is always a rendered server-entry object `{ ... }` (never a bare string or
  # array), so its end is simply the matching close brace — no generic JSON-value-end scan needed.
  snKey = findKey(content, objOpen + 1, objClose - 1, server)
  if (snKey >= 0) {
    colon2 = indexOfCharFrom(content, snKey, ":")
    vs = indexOfCharFrom(content, colon2, "{")
    ve = matchBracket(content, vs) + 1
    printf "%s%s%s", substr(content, 1, vs - 1), entry, substr(content, ve)
  } else {
    hadEntries = (trimStr(substr(content, objOpen + 1, objClose - objOpen - 1)) != "")
    ins = "\n    \"" server "\": " entry (hadEntries ? "," : "")
    printf "%s%s%s", substr(content, 1, objOpen), ins, substr(content, objOpen + 1)
  }
}
AWK_EOF

  cat > "$CACHE/lib/toml-merge.awk" <<'AWK_EOF'
BEGIN {
  header = ENVIRON["MCPM_HEADER"]
  fresh = ENVIRON["MCPM_FRESH"]
}

{ lines[++n] = $0 }

END {
  if (n == 0) { printf "%s\n", fresh; exit }

  idx = 0
  for (i = 1; i <= n; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    if (t == header) { idx = i; break }
  }

  if (idx == 0) {
    for (i = 1; i <= n; i++) print lines[i]
    print ""
    printf "%s\n", fresh
    exit
  }

  endi = n + 1
  for (i = idx + 1; i <= n; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t)
    if (substr(t, 1, 1) == "[") { endi = i; break }
  }

  for (i = 1; i < idx; i++) print lines[i]
  printf "%s\n", fresh
  for (i = endi; i <= n; i++) print lines[i]
}
AWK_EOF

  cat > "$CACHE/lib/yaml-merge.awk" <<'AWK_EOF'
BEGIN {
  itemNameLine = ENVIRON["MCPM_ITEM_NAME_LINE"]
  item = ENVIRON["MCPM_ITEM"]
  freshFull = ENVIRON["MCPM_FRESH_FULL"]
}

{ lines[++n] = $0 }

END {
  if (n == 0) { printf "%s\n", freshFull; exit }

  msIdx = 0
  for (i = 1; i <= n; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    if (t == "mcpServers:") { msIdx = i; break }
  }

  if (msIdx == 0) {
    for (i = 1; i <= n; i++) print lines[i]
    print "mcpServers:"
    printf "%s\n", item
    exit
  }

  blockEnd = n + 1
  for (i = msIdx + 1; i <= n; i++) {
    t = lines[i]
    if (length(t) == 0) continue
    first = substr(t, 1, 1)
    tt = t
    gsub(/^[ \t]+/, "", tt); gsub(/[ \t]+$/, "", tt)
    if (tt != "" && first != " ") { blockEnd = i; break }
  }

  itemIdx = 0
  for (i = msIdx + 1; i < blockEnd; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    if (t == itemNameLine) { itemIdx = i; break }
  }

  if (itemIdx == 0) {
    for (i = 1; i <= msIdx; i++) print lines[i]
    printf "%s\n", item
    for (i = msIdx + 1; i <= n; i++) print lines[i]
    exit
  }

  indentLine = lines[itemIdx]
  indent = 0
  while (substr(indentLine, indent + 1, 1) == " ") indent++

  e = blockEnd
  for (i = itemIdx + 1; i < blockEnd; i++) {
    li = lines[i]
    liIndent = 0
    while (substr(li, liIndent + 1, 1) == " ") liIndent++
    trimmed = li
    gsub(/^[ \t]+/, "", trimmed)
    if (liIndent == indent && substr(trimmed, 1, 2) == "- ") { e = i; break }
  }

  for (i = 1; i < itemIdx; i++) print lines[i]
  printf "%s\n", item
  for (i = e; i <= n; i++) print lines[i]
}
AWK_EOF
}

write_client_configs() {
  local _project _client _command _cpfile _clients c _t _relpath _fmt _cmd_esc _proj_esc _cp_esc
  local _entry _header _fresh _item _itemline _freshfull _out _tmp
  _project="$1"; _client="$2"; _command="$3"; _cpfile="$4"
  if [ "$_client" = "all" ]; then _clients="$ALL_CLIENTS"; else _clients="$_client"; fi
  write_awk_libs
  _cmd_esc=$(json_escape "$_command")
  _proj_esc=$(json_escape "$_project")
  _cp_esc=$(json_escape "$_cpfile")
  for c in $_clients; do
    _t=$(target_for "$c")
    [ -n "$_t" ] || { echo "scalasemantic-mcp: unsupported client '$c'" >&2; continue; }
    _relpath=$(echo "$_t" | cut -d' ' -f1)
    _fmt=$(echo "$_t" | cut -d' ' -f2)
    _out="$_project/$_relpath"
    mkdir -p "$(dirname "$_out")"
    [ -f "$_out" ] || : > "$_out"
    _tmp=$(mktemp)
    case "$_fmt" in
      json)
        _entry="{
      \"command\": \"$_cmd_esc\",
      \"args\": [\"serve\", \"$_proj_esc\", \"$_cp_esc\"]$(extra_json_fields "$c")
    }"
        MCPM_SERVER="scala-semantic" MCPM_ENTRY="$_entry" \
          awk -f "$CACHE/lib/json-merge.awk" "$_out" > "$_tmp"
        ;;
      toml)
        _header="[mcp_servers.scala-semantic]"
        _fresh="[mcp_servers.scala-semantic]
command = \"$_cmd_esc\"
args = [\"serve\", \"$_proj_esc\", \"$_cp_esc\"]
startup_timeout_sec = 60
tool_timeout_sec = 60"
        MCPM_HEADER="$_header" MCPM_FRESH="$_fresh" \
          awk -f "$CACHE/lib/toml-merge.awk" "$_out" > "$_tmp"
        ;;
      yaml)
        _itemline="- name: \"scala-semantic\""
        _item="  - name: \"scala-semantic\"
    command: \"$_cmd_esc\"
    args:
      - \"serve\"
      - \"$_proj_esc\"
      - \"$_cp_esc\"
    connectionTimeout: 60000"
        _freshfull="name: ScalaSemantic MCP
version: 1.0.0
schema: v1
mcpServers:
$_item"
        MCPM_ITEM_NAME_LINE="$_itemline" MCPM_ITEM="$_item" MCPM_FRESH_FULL="$_freshfull" \
          awk -f "$CACHE/lib/yaml-merge.awk" "$_out" > "$_tmp"
        ;;
    esac
    mv -f "$_tmp" "$_out"
    echo "scalasemantic-mcp: wrote $_out" >&2
  done
}

classpath_file_for_project() {
  local _project
  _project="$1"
  mkdir -p "$_project/.scala-semantic"
  if ls "$_project"/*.sbt >/dev/null 2>&1; then
    printf '%s/.scala-semantic/classpath-sbt.json' "$_project"
  elif [ -f "$_project/build.mill" ] || [ -f "$_project/build.sc" ]; then
    printf '%s/.scala-semantic/classpath-mill.json' "$_project"
  elif [ -f "$_project/project.scala" ] || ls "$_project"/*.scala >/dev/null 2>&1 || ls "$_project"/*.sc >/dev/null 2>&1; then
    printf '%s/.scala-semantic/classpath-scala-cli.json' "$_project"
  else
    printf '%s/.scala-semantic/classpath.json' "$_project"
  fi
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

  _cpfile=$(classpath_file_for_project "$_project")

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
