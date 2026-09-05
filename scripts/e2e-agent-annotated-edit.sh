#!/usr/bin/env bash
# End-to-end check of the CLAUDE-CODE HARNESS side of the annotated read/write path.
#
# demo-annotated-write.sc proves the SERVER does the right thing when driven directly over
# JSON-RPC. This script proves the AGENT is actually driven onto that path: it boots a real
# `claude` session against a throwaway Scala project wired with the ScalaSemantic MCP server
# and the PreToolUse guard hook, asks it to make a code change, and then asserts:
#
#   1. the guard denied / redirected the text tools (Read, grep, ...) on .scala sources
#   2. the agent READ the file through annotated_source and the buffer it saw carried
#      /*SEM:...:SEM*/ enrichment
#   3. the agent WROTE through annotated_source, sending the enriched buffer back
#   4. the file that landed on DISK is changed but carries no enrichment
#
# Usage:
#   scripts/e2e-agent-annotated-edit.sh [--keep] [--model <id>] [--strict-edits]
#
# --strict-edits regenerates the guard with edit-denial on, which makes assertion (3) a hard
# requirement rather than a reminder the agent may reasonably decline for a one-line change.
#
# Env: SCALASEMANTIC_JAR overrides the server jar (default out/mcp/assembly.dest/out.jar).

set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
jar="${SCALASEMANTIC_JAR:-$repo_root/out/mcp/assembly.dest/out.jar}"
keep=0
model=()
strict_edits=0

while [ $# -gt 0 ]; do
  case "$1" in
    --keep)          keep=1 ;;
    --strict-edits)  strict_edits=1 ;;
    --model)         shift; model=(--model "$1") ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

for tool in claude scala-cli jq; do
  command -v "$tool" >/dev/null || { echo "error: $tool not on PATH" >&2; exit 1; }
done

if [ ! -f "$jar" ]; then
  echo "server jar missing, building: ./mill mcp.assembly"
  (cd "$repo_root" && ./mill mcp.assembly >/dev/null)
fi

sandbox=$(mktemp -d -t scalasemantic-e2e)
cleanup() { if [ "$keep" = 1 ]; then echo "kept: $sandbox"; else rm -rf "$sandbox"; fi; }
trap cleanup EXIT

proj="$sandbox/demo-project"
mkdir -p "$proj/.claude/hooks"

cat >"$proj/project.scala" <<'EOF'
//> using scala 3.8.4
EOF

# Inference-heavy on purpose: an inferred return type, an inferred lambda param, and a `max`
# that only resolves through the given Ordering — the three things enrichment shows and raw
# text does not.
cat >"$proj/Fixture.scala" <<'EOF'
object Fixture:
  given byLength: Ordering[String] = Ordering.by(s => s.length)

  def sizes(xs: List[String]) = xs.map(s => s.length)

  def longest(xs: List[String]) = xs.max

  val total = sizes(List("a", "bb", "ccc")).sum
EOF

echo "── compiling fixture with SemanticDB ──"
(cd "$proj" && scala-cli compile --semanticdb --semanticdb-sourceroot . \
  --semanticdb-targetroot semanticdb . >/dev/null)

cat >"$proj/.mcp.json" <<EOF
{
  "mcpServers": {
    "scala-semantic": {
      "command": "java",
      "args": ["-cp", "$jar", "com.github.mercurievv.scalasemantic.mcpServer", "$proj"]
    }
  }
}
EOF

# The guard the server's own setup installs. Copied rather than regenerated so this test
# exercises the shipped hook verbatim.
cp "$repo_root/.claude/hooks/scala-semantic-guard.sh" "$proj/.claude/hooks/"
chmod +x "$proj/.claude/hooks/scala-semantic-guard.sh"
if [ "$strict_edits" = 1 ]; then
  sed -i '' 's/^strict_edits=0$/strict_edits=1/' "$proj/.claude/hooks/scala-semantic-guard.sh"
fi

cat >"$proj/.claude/settings.json" <<'EOF'
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Read|Grep|Glob|Bash|Edit|Write|MultiEdit",
        "hooks": [
          { "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/scala-semantic-guard.sh" }
        ]
      }
    ]
  }
}
EOF

before_hash=$(shasum -a 256 "$proj/Fixture.scala" | cut -d' ' -f1)
log="$sandbox/session.jsonl"

prompt='In Fixture.scala, rename the method `sizes` to `lengths` and update every use of it. Keep everything else identical.'

echo
echo "── running claude in $proj ──"
set +e
(cd "$proj" && claude -p "$prompt" \
  --output-format stream-json --verbose \
  --permission-mode bypassPermissions \
  --mcp-config .mcp.json --strict-mcp-config \
  ${model[@]+"${model[@]}"} ) >"$log" 2>"$sandbox/claude.err"
claude_rc=$?
set -e

if [ ! -s "$log" ]; then
  echo "error: claude produced no output (rc=$claude_rc)" >&2
  sed -n 1,40p "$sandbox/claude.err" >&2
  exit 1
fi

# --- what the console actually showed --------------------------------------------------

echo
echo "── tool calls the agent made ──"
jq -r 'select(.type=="assistant") | .message.content[]? | select(.type=="tool_use")
       | "  " + .name
         + (if (.input | type == "object" and has("write")) then "   [WRITE]" else "" end)
         + (if .name=="Bash" then "  $ " + (.input.command // "" | .[0:70]) else "" end)' "$log"

echo
echo "── guard hook verdicts (one line per intercepted call) ──"
jq -r '.message.content[]? | select(.type=="tool_result")
       | (.content // "" | if type=="array" then (map(.text? // "") | join("\n")) else . end)
       | select(test("ScalaSemantic"))
       | "  " + (split("\n")[0] | .[0:120])' "$log"

# --- assertions ------------------------------------------------------------------------

pass=0; fail=0
# `set -e` must not kill the run on the first failing assertion — collect them all.
try() { assert_rc=0; "$@" >/dev/null 2>&1 || assert_rc=$?; }
check() { # check <ok:0|1> <message>
  if [ "$1" = 0 ]; then echo "  ✓ $2"; pass=$((pass+1))
  else echo "  ✗ $2"; fail=$((fail+1)); fi
}

echo
echo "── assertions ──"

try jq -e 'select(.type=="assistant") | .message.content[]?
       | select(.type=="tool_use" and .name=="mcp__scala-semantic__annotated_source")' "$log"
check $assert_rc "agent called annotated_source"

# The READ the agent saw must have carried enrichment sentinels.
try jq -e '.message.content[]? | select(.type=="tool_result")
       | (.content // "" | if type=="array" then (map(.text? // "") | join("\n")) else . end)
       | select(test("SEM:"))' "$log"
check $assert_rc "the buffer the agent READ carried /*SEM:...:SEM*/ enrichment"

# The WRITE must have gone back through annotated_source, enrichment still in the payload.
try jq -e 'select(.type=="assistant") | .message.content[]?
       | select(.type=="tool_use" and .name=="mcp__scala-semantic__annotated_source")
       | select((.input.write? // "") | test("SEM:"))' "$log"
wrote_annotated=$assert_rc
if [ "$strict_edits" = 1 ]; then
  check $wrote_annotated "agent WROTE the enriched buffer back through annotated_source"
else
  if [ "$wrote_annotated" = 0 ]; then
    check 0 "agent WROTE the enriched buffer back through annotated_source"
  else
    echo "  ~ agent edited without the annotated write path (allowed: guard only reminds;"
    echo "    re-run with --strict-edits to make this a hard requirement)"
  fi
fi

after_hash=$(shasum -a 256 "$proj/Fixture.scala" | cut -d' ' -f1)
try [ "$before_hash" != "$after_hash" ]
check $assert_rc "the file on disk was modified"

try grep -q 'lengths' "$proj/Fixture.scala"
check $assert_rc "the requested change (sizes -> lengths) is on disk"

assert_rc=0; grep -q 'SEM:' "$proj/Fixture.scala" >/dev/null 2>&1 && assert_rc=1
check $assert_rc "the file on disk carries NO enrichment"

try sh -c 'cd "$0" && scala-cli compile .' "$proj"
check $assert_rc "the written file still compiles"

echo
echo "── Fixture.scala on disk ──"
sed 's/^/  /' "$proj/Fixture.scala"

echo
echo "passed: $pass   failed: $fail"
[ "$fail" = 0 ]
