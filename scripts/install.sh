#!/usr/bin/env sh
# One-time installer for the ScalaSemantic MCP launcher (Linux / macOS).
#
# Installs the auto-download launcher to a STABLE location on PATH so a `.mcp.json` entry can point
# at a fixed path — independent of where this repo is cloned, and surviving `sbt clean` (unlike the
# dev launcher under target/). The launcher then fetches + caches the fat jar itself on first run.
#
# Usage:   curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/install.sh | sh
#   or:     scripts/install.sh            (from a checkout — installs the local launcher)
#
# Override the install dir with BIN_DIR=/somewhere/on/PATH. Requires curl or wget (network install).
set -eu

REPO="MercurieVV/ScalaSemantic"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
DEST="$BIN_DIR/scalasemantic-mcp"
LAUNCHER_NAME="scalasemantic-mcp.sh"

mkdir -p "$BIN_DIR"

# Prefer a sibling launcher when run from a checkout; otherwise download it from the repo.
SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$SELF_DIR/$LAUNCHER_NAME" ]; then
  cp "$SELF_DIR/$LAUNCHER_NAME" "$DEST"
else
  URL="https://raw.githubusercontent.com/$REPO/master/scripts/$LAUNCHER_NAME"
  echo "scalasemantic-mcp: downloading launcher from $URL" >&2
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$DEST"
  elif command -v wget >/dev/null 2>&1; then wget -qO "$DEST" "$URL"
  else echo "scalasemantic-mcp: need curl or wget to install" >&2; exit 1
  fi
fi
chmod +x "$DEST"

echo "Installed: $DEST" >&2
case ":$PATH:" in
  *":$BIN_DIR:"*) : ;;
  *) echo "NOTE: $BIN_DIR is not on PATH — add it, or use the absolute path below." >&2 ;;
esac

cat >&2 <<EOF

Register it with your MCP client (.mcp.json), pointing args at the project to analyze:

{
  "mcpServers": {
    "scala-semantic": {
      "command": "$DEST",
      "args": ["/abs/path/to/project-to-analyze"]
    }
  }
}
EOF
