#!/usr/bin/env bash

# Retired no-op.
#
# Docs no longer pin a concrete plugin version: README.md and
# docs/getting-started/integration.md show an `x.y.z` placeholder and point readers at the Maven
# Central badge / latest GitHub release. There is nothing to rewrite per release, so this script does
# nothing — it stays only so the existing post-release CI step keeps succeeding without a workflow
# change.
#
# Usage (argument ignored): scripts/update-doc-versions.sh <version-or-tag>

set -euo pipefail
echo "update-doc-versions.sh: retired no-op (docs use an x.y.z placeholder); nothing to do."
