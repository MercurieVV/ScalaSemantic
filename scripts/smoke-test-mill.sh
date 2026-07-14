#!/usr/bin/env sh
# End-to-end launcher smoke test for ScalaSemantic MCP server against a real Mill project.
# Unlike scripts/smoke-test.sh (synthetic scala-cli fixture, PC-backed type_at_position), this
# dogfoods the launcher directly against THIS repo's own Mill-emitted SemanticDB, run from the
# project root, proving index-based tools work with `*.semanticdb` produced by `./mill __.compile`
# (no classpath metadata / presentation-compiler involved).
set -eu

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

ASSEMBLY_JAR="$REPO_ROOT/out/mcp/assembly.dest/out.jar"
if [ ! -f "$ASSEMBLY_JAR" ]; then
  echo "Error: $ASSEMBLY_JAR missing. Run './mill mcp.assembly' first." >&2
  exit 1
fi

SEMANTICDB_COUNT=$(find "$REPO_ROOT/out" -path '*META-INF/semanticdb*' -name '*.semanticdb' 2>/dev/null | wc -l | tr -d ' ')
if [ "$SEMANTICDB_COUNT" -eq 0 ]; then
  echo "Error: no *.semanticdb files under $REPO_ROOT/out. Run './mill __.compile' first." >&2
  exit 1
fi
echo "Found $SEMANTICDB_COUNT emitted SemanticDB files (Mill-generated)."

CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"
mkdir -p "$CACHE_DIR"
echo "Copying local assembly jar to cache..."
cp "$ASSEMBLY_JAR" "$CACHE_DIR/scalasemantic-mcp-local.jar"

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

STDOUT_LOG="$TEMP_DIR/stdout.log"
STDERR_LOG="$TEMP_DIR/stderr.log"

echo "Launching server with root = repo root (real Mill project, no explicit classpath arg)..."
# A real symbol from this repo's own build: SemanticIndex.fromProject(projectRoot: String): SemanticIndex
(
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}\n'
  printf '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n'
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"method_signature","arguments":{"symbol":"com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex.fromProject()."}}}\n'
  printf '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_usages","arguments":{"symbol":"com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex.fromProject()."}}}\n'
  sleep 4
) | SCALASEMANTIC_VERSION=local "$REPO_ROOT/scripts/scalasemantic-mcp.sh" serve "$REPO_ROOT" --log > "$STDOUT_LOG" 2> "$STDERR_LOG" || true

fail() {
  echo "Error: $1" >&2
  echo "--- stdout ---" >&2
  cat "$STDOUT_LOG" >&2
  echo "--- stderr ---" >&2
  cat "$STDERR_LOG" >&2
  exit 1
}

if ! grep -q '"id":2' "$STDOUT_LOG"; then
  fail "did not receive response for method_signature tools/call"
fi

if ! grep -q 'projectRoot' "$STDOUT_LOG"; then
  fail "expected method_signature to resolve real signature of SemanticIndex.fromProject with parameter projectRoot"
fi

if ! grep -q '"id":3' "$STDOUT_LOG"; then
  fail "did not receive response for find_usages tools/call"
fi

if ! grep -q '"symbol"' "$STDOUT_LOG"; then
  fail "expected find_usages to return at least one usage reference"
fi

echo "=== Mill E2E Smoke Test Passed Successfully ==="
