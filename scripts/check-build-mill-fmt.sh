#!/usr/bin/env bash
# scalafmt-checks build.mill itself (repo root). build.mill lives in its own Mill evaluator
# context (meta-level 1: the build that configures the main project's modules), unreachable from
# a normal level-0 Task.Command — see build.mill's comment above this script's invocation for why
# this MUST stay a standalone script and never get wrapped in a Mill Task.Command: a Task.Command
# that shells out to `./mill --meta-level N ...` deadlocks against its own daemon (Mill serializes
# all commands through one daemon per project), confirmed by a 15+ minute hang that had to be
# killed manually. Run this from a shell (locally or as its own CI step), never from inside Mill.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
./mill --meta-level 1 mill.scalalib.scalafmt/checkFormatAll sources
