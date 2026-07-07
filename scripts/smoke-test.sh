#!/usr/bin/env sh
# End-to-end launcher smoke test for ScalaSemantic MCP server.
# Runs the server through the same process boundary users exercise, using the compiled local assembly jar.
set -eu

# Cache directory used by the launcher
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp"
mkdir -p "$CACHE_DIR"

# Copy local assembly jar to the cache as 'local' version
echo "Copying out/mcp/assembly.dest/out.jar to cache..."
cp out/mcp/assembly.dest/out.jar "$CACHE_DIR/scalasemantic-mcp-local.jar"

# Create a temporary directory for the test project workspace
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

echo "Created temporary workspace: $TEMP_DIR"

# Create a minimal Scala source file
cat > "$TEMP_DIR/Widget.scala" <<'EOF'
package demo

object Widget {
  def value: String = "ok"
}
EOF

echo "Running setup on temporary project..."
# We run setup using the local script. We specify --project and client.
# This generates the scala-cli / project.scala setup and prints classpath.
scripts/scalasemantic-mcp.sh setup --project "$TEMP_DIR" --client generic

# Verify that setup successfully generated the classpath file
CP_FILE="$TEMP_DIR/.scala-semantic/classpath-scala-cli.json"
if [ ! -f "$CP_FILE" ]; then
  echo "Error: setup failed to generate $CP_FILE" >&2
  exit 1
fi

echo "Verifying E2E server launch with valid classpath (PC enabled)..."

STDOUT_VALID="$TEMP_DIR/stdout-valid.log"
STDERR_VALID="$TEMP_DIR/stderr-valid.log"

# Run serve using the local launcher script, forcing it to use our cached 'local' jar
(
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}\n'
  printf '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n'
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"type_at_position","arguments":{"uri":"Widget.scala","line":3,"character":6,"source":"package demo\\n\\nobject Widget {\\n  def value: String = \\"ok\\"\\n}\\n"}}}\n'
  sleep 4
) | SCALASEMANTIC_VERSION=local scripts/scalasemantic-mcp.sh serve "$TEMP_DIR" "$CP_FILE" --log > "$STDOUT_VALID" 2> "$STDERR_VALID" || true

# Verify tools/call output
if ! grep -q '"id":2' "$STDOUT_VALID"; then
  echo "Error: did not receive response for tools/call request in valid run" >&2
  echo "Stdout of valid run:" >&2
  cat "$STDOUT_VALID" >&2
  echo "Stderr of valid run:" >&2
  cat "$STDERR_VALID" >&2
  if [ -f "$TEMP_DIR/scala-semantic-mcp.log" ]; then
    echo "Server Log of valid run:" >&2
    cat "$TEMP_DIR/scala-semantic-mcp.log" >&2
  fi
  exit 1
fi

if ! grep -q 'symbol' "$STDOUT_VALID"; then
  echo "Error: expected type_at_position to succeed with a resolved symbol in valid run" >&2
  echo "Stdout of valid run:" >&2
  cat "$STDOUT_VALID" >&2
  exit 1
fi

if ! grep -q 'demo/Widget\.value' "$STDOUT_VALID"; then
  echo "Error: expected type_at_position to resolve Widget.value symbol" >&2
  echo "Stdout of valid run:" >&2
  cat "$STDOUT_VALID" >&2
  exit 1
fi

echo "Verifying E2E server launch with missing classpath (PC disabled)..."
STDOUT_INVALID="$TEMP_DIR/stdout-invalid.log"
STDERR_INVALID="$TEMP_DIR/stderr-invalid.log"

(
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}\n'
  printf '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n'
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"type_at_position","arguments":{"uri":"Widget.scala","line":3,"character":6,"source":"package demo\\n\\nobject Widget {\\n  def value: String = \\"ok\\"\\n}\\n"}}}\n'
  sleep 4
) | SCALASEMANTIC_VERSION=local scripts/scalasemantic-mcp.sh serve "$TEMP_DIR" "$TEMP_DIR/nonexistent.json" --log > "$STDOUT_INVALID" 2> "$STDERR_INVALID" || true

if ! grep -q '"id":2' "$STDOUT_INVALID"; then
  echo "Error: did not receive response for tools/call request in invalid run" >&2
  echo "Stdout of invalid run:" >&2
  cat "$STDOUT_INVALID" >&2
  echo "Stderr of invalid run:" >&2
  cat "$STDERR_INVALID" >&2
  exit 1
fi

if ! grep -q 'found' "$STDOUT_INVALID"; then
  echo "Error: expected type_at_position to fail with found:false in invalid run (missing classpath)" >&2
  echo "Stdout of invalid run:" >&2
  cat "$STDOUT_INVALID" >&2
  exit 1
fi

echo "=== E2E Smoke Test Passed Successfully ==="
