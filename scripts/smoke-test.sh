#!/usr/bin/env sh
# End-to-end launcher smoke test for ScalaSemantic MCP server.
# Runs the server through the same process boundary users exercise, using the compiled local assembly jar.
set -eu

# Resolve the repository root directory (the parent of the scripts directory)
SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

# Jar directory used by the launcher (ADR-0004 moved it out of the cache: an installed release jar
# is data, not a cache, and must not be evicted by a cache cleaner).
CACHE_DIR="${SCALASEMANTIC_HOME:-$HOME/.local/share/scalasemantic-mcp}"
mkdir -p "$CACHE_DIR"

# Copy local assembly jar to the cache as 'local' version
echo "Copying out/mcp/assembly.dest/out.jar to cache..."
cp "$REPO_ROOT/out/mcp/assembly.dest/out.jar" "$CACHE_DIR/scalasemantic-mcp-local.jar"

# Create a temporary directory for the test project workspace
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

echo "Created temporary workspace: $TEMP_DIR"

# Create a minimal Scala source file under a hidden parent directory. That keeps the fallback
# visible-directory scan from finding classpath metadata, so the valid run must use modules JSON.
MODULE_DIR="$TEMP_DIR/.modules/fixture"
mkdir -p "$MODULE_DIR"
cat > "$MODULE_DIR/Widget.scala" <<'EOF'
package demo

object Widget {
  def value: String = "ok"
}
EOF

echo "Running setup on temporary project..."
# We run setup using the local script. We specify --project and client.
# This generates the scala-cli / project.scala setup and prints classpath.
"$REPO_ROOT/scripts/scalasemantic-mcp.sh" setup --project "$MODULE_DIR" --client generic

# Verify that setup successfully generated the classpath file
CP_FILE="$MODULE_DIR/.scala-semantic/classpath-scala-cli.json"
if [ ! -f "$CP_FILE" ]; then
  echo "Error: setup failed to generate $CP_FILE" >&2
  exit 1
fi

TMP_CP="$TEMP_DIR/classpath-scala-cli.tmp"
awk '
  /"baseDir":/ { sub(/"baseDir": "."/, "\"baseDir\": \".modules/fixture\""); print; next }
  /"classpath": \[/ { in_cp = 1; print; next }
  in_cp && /^[[:space:]]*]/ { in_cp = 0; print; next }
  in_cp && /^[[:space:]]*"/ && $0 !~ /^[[:space:]]*"\// {
    sub(/"/, "\".modules/fixture/")
  }
  { print }
' "$CP_FILE" > "$TMP_CP"
mv "$TMP_CP" "$CP_FILE"

mkdir -p "$TEMP_DIR/.scala-semantic"
cat > "$TEMP_DIR/.scala-semantic/modules-scala-cli.json" <<'EOF'
{
  "schemaVersion": 1,
  "buildTool": "scala-cli",
  "parent": {
    "name": "root",
    "path_from_root": ".",
    "path_to_out_dir": "."
  },
  "modules": [
    {
      "name": "fixture",
      "path_from_root": ".modules/fixture",
      "path_to_out_dir": ".modules/fixture"
    }
  ]
}
EOF

echo "Verifying E2E server launch with valid classpath (PC enabled)..."

STDOUT_VALID="$TEMP_DIR/stdout-valid.log"
STDERR_VALID="$TEMP_DIR/stderr-valid.log"

# Run serve using the local launcher script, forcing it to use our cached 'local' jar
(
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}\n'
  printf '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n'
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"type_at_position","arguments":{"uri":".modules/fixture/Widget.scala","line":3,"character":6,"source":"package demo\\n\\nobject Widget {\\n  def value: String = \\"ok\\"\\n}\\n"}}}\n'
  sleep 4
) | SCALASEMANTIC_VERSION=local "$REPO_ROOT/scripts/scalasemantic-mcp.sh" serve "$TEMP_DIR" --log > "$STDOUT_VALID" 2> "$STDERR_VALID" || true

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

if ! grep -q '.modules/fixture/.scala-semantic/classpath-scala-cli.json' "$TEMP_DIR/scala-semantic-mcp.log"; then
  echo "Error: expected valid run to discover hidden child module classpath metadata" >&2
  cat "$TEMP_DIR/scala-semantic-mcp.log" >&2
  exit 1
fi

echo "Verifying E2E server launch with missing classpath (PC disabled)..."
STDOUT_INVALID="$TEMP_DIR/stdout-invalid.log"
STDERR_INVALID="$TEMP_DIR/stderr-invalid.log"

(
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}\n'
  printf '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n'
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"type_at_position","arguments":{"uri":".modules/fixture/Widget.scala","line":3,"character":6,"source":"package demo\\n\\nobject Widget {\\n  def value: String = \\"ok\\"\\n}\\n"}}}\n'
  sleep 4
) | SCALASEMANTIC_VERSION=local "$REPO_ROOT/scripts/scalasemantic-mcp.sh" serve "$TEMP_DIR" "$TEMP_DIR/nonexistent.json" --log > "$STDOUT_INVALID" 2> "$STDERR_INVALID" || true

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

# Write E2E test results for regression tracking
mkdir -p "$REPO_ROOT/out"
cat > "$REPO_ROOT/out/e2e-report.json" <<'EOF'
[
  "E2E: Verifying E2E server launch with valid classpath (PC enabled)",
  "E2E: Verifying E2E server launch with missing classpath (PC disabled)"
]
EOF

echo "=== E2E Smoke Test Passed Successfully ==="

