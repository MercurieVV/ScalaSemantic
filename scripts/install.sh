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

# Warm the cache now so the FIRST MCP connect hits a ready jar instead of racing the client's
# (~30s) connection timeout while an ~88 MB jar downloads. Best-effort: never fail the install.
echo "scalasemantic-mcp: prefetching the server jar (one-time download) ..." >&2
"$DEST" --prefetch . 1>&2 || echo "scalasemantic-mcp: prefetch skipped (will download on first run)" >&2
case ":$PATH:" in
  *":$BIN_DIR:"*) : ;;
  *) echo "NOTE: $BIN_DIR is not on PATH — add it, or use the absolute path below." >&2 ;;
esac

cat >&2 <<EOF

Register it once per project, from that project's root:

  cd /path/to/your-project && $DEST setup --client all

...or by hand (run from inside the project you're analyzing so its own client picks up cwd = project root; see ADR-0003):

{
  "mcpServers": {
    "scala-semantic": {
      "command": "$DEST",
      "args": ["serve", "."]
    }
  }
}
EOF
