#!/usr/bin/env bash

# Point git at the repo's .githooks directory and make the hooks executable.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

echo "Configured git hooks path to .githooks"
echo "Installed: $(ls .githooks 2>/dev/null | tr '\n' ' ')"
