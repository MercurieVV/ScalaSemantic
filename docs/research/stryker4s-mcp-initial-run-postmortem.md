# Postmortem: `mcp` mutation testing `InitialTestRunFailedException`

## Summary

`./mill mcpStryker.stryker` failed on every attempt with
`stryker4s.exception.InitialTestRunFailedException: Initial test run failed. Please make sure your
tests pass before running Stryker4s.` — with zero diagnostic detail in stryker4s's own logs, even
with every documented debug flag enabled. Two independent bugs were compounding:

1. **Wrong working directory for the forked test-runner subprocess** (the real blocker).
2. **A slow test exceeding munit's default per-test timeout** under concurrent mutation-testing
   load (surfaced only once bug 1 was fixed).

Neither was a bug in this repo's production code. Both are now fixed; `mcpStryker.stryker` runs
mutants successfully (`Initial test run succeeded! Testing mutants...`).

## Root cause 1: `--base-dir` mismatch

stryker4s's Mill plugin (`stryker4s.mill.Stryker4sModule`, in the unreleased Mill plugin vendored at
`vendor/stryker4s-mill/`) constructs its `MillConfigSource` with:

```scala
baseDirValue = fs2.io.file.Path.fromNioPath(moduleDir.toNIO)
```

i.e. `config.baseDir` is always the *mutated module's own directory* (`mcp/`), never the repo root.
`ProcessTestRunner.createProcess` then spawns the forked test-runner JVM with
`.withWorkingDirectory(config.baseDir)` — so the subprocess's cwd is `mcp/`, not the repo root.

`McpSuite` dogfoods the analyzer against this repo's own SemanticDB output via
`SemanticIndex.fromProject(".")`. Per this project's convention (see `CLAUDE.md`), that call assumes
cwd = repo root, because SemanticDB output lives under `out/<module>/compile.dest/classes/META-INF/
semanticdb/**` for *every* module, not just `mcp/`. Under the wrong cwd, `fromProject(".")` silently
resolves to `mcp/` and finds no SemanticDB data at all — the index loads empty, and every
SemanticDB-backed assertion in `McpSuite` returns nothing (empty symbol sets, `count == 0`, etc.).

The failure shape made this hard to see: sbt-testing-interface reports the whole run as
`Status.Failure`, but stryker4s's `InitialTestRunEventHandler` (used only for the initial,
pre-mutation test run — as opposed to `MutantRunEventHandler`, used for actual mutants) never
records *which* tests failed or why; it only tracks the aggregate status. So the only thing that
ever reached stryker4s's own logging was the generic `InitialTestRunFailedException`, with the
actual assertion failures and their causes discarded before they could be logged anywhere.

### How it was found

Trying to enable stryker4s's own `--log-test-runner-stdout` diagnostic flag first surfaced a smaller,
separate documentation bug: the correct CLI flag is a bare `--log-test-runner-stdout` (no value, no
`debug.` prefix) — the `debug.log-test-runner-stdout` dotted key only exists in the *file* config
source (`stryker4s.conf`), not the CLI parser (`CliConfigSource`). Even fixed, though, the pipe from
subprocess stdout back through stryker4s's own logger requires Mill's `-d`/`--debug` flag (since the
pipe forwards lines into `log.debug(...)`, gated by `millLogger.debugEnabled`) — and even with both
flags right, nothing showed, because `InitialTestRunEventHandler` had nothing to say in the first
place.

Getting the real signal required cloning stryker4s from source (pinned at commit `86aa9ed1`, the
same commit the vendored jar was built from) and patching temporary diagnostic logging directly into
the test-runner subprocess — writing straight to a fixed file path
(`/tmp/stryker4s-testrunner-debug.log`), bypassing stryker4s's own logger entirely, to rule out any
remaining doubt about whether output was being captured. That surfaced the real munit assertion
failures (`count > 0` failing with `count == 0`, `assertEquals(kinds, Set("TRAIT"))` failing with
`Set()`, etc.) — all symptomatic of an empty `SemanticIndex`. From there the `baseDir` mismatch was
straightforward to confirm by reading `MillConfigSource.scala`, and to fix by passing an explicit
`--base-dir` on the CLI: stryker4s's `CliConfigSource` has `ConfigOrder(5)`, which outranks
`MillConfigSource`'s `ConfigOrder(15)` (lower number wins), so an explicit CLI flag cleanly overrides
the Mill plugin's hardcoded default without touching stryker4s itself at all.

**No stryker4s patch was needed or kept.** The debug-logging clone was used purely for diagnosis; the
vendored jar in `vendor/stryker4s-mill/` was rebuilt clean from the same pinned upstream commit
after the investigation concluded, with `build.mill` and the local overlay reverted to committed
state throughout.

### Fix

`scripts/run-stryker.sh`:
- `run_module` now passes `--base-dir "$run_dir"` (the repo root for `--local`, or the worktree's
  absolute path otherwise) to every module's `mcpStryker`-style invocation.
- `module_mutate_patterns` globs are now repo-root-relative (prefixed with the module's own
  directory, e.g. `mcp/src/main/scala/**/*.scala`), since `--mutate` patterns are resolved relative
  to `--base-dir` too.

## Root cause 2: `smart_code_duplications` timeout under concurrent load

Once the initial test run's cwd was fixed, the real 28-test `McpSuite` suite ran for real — and
`smart_code_duplications` (which scans the whole project for duplicate code) intermittently exceeded
munit's default 30-second per-test timeout when 5 concurrent stryker4s test-runner JVMs were
competing for CPU. This is a resource-contention artifact of mutation testing's concurrency, not a
regression in the test or the tool it exercises.

### Fix

`McpSuite` now overrides `munitTimeout` to 120 seconds.

## What's unaffected

- `analysis`'s stryker4s run doesn't dogfood live SemanticDB (`CompatSuite`/`ModelsSuite`/etc. run
  against fixed, committed fixtures), so it was never exposed to root cause 1.
- `core`'s `SemanticIndexSuite` *does* dogfood and would hit the same `baseDir` bug, but `core` isn't
  currently wired as a reliable CI module for unrelated structural reasons (see `mutation.yml`'s
  header comment) — the `--base-dir` fix applies uniformly to all modules regardless, so `core`
  inherits the fix for whenever it's re-enabled.

## Follow-up

- Tracked in [GitHub issue link — see PR description for this fix].
- `docs/research/stryker4s-alert-strategy.md` and the `mutation.yml` header comment have been kept
  in sync with this finding.
