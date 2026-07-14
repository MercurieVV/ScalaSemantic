# ScalaSemantic

MCP server doing deep semantic analysis on Scala projects via SemanticDB — capabilities beyond standard LSP/Metals.

## Stack
- Scala 3.8.4, Mill 1.1.7 (build tool; `build.sbt`/`project/` deleted — Sonatype publish and
  stryker4s mutation testing are deferred, disabled in CI (`if: false`) until each has a working
  Mill path, see `docs/MILL_MIGRATION.md`)
- `org.scalameta:scalameta:4.13.9` — SemanticDB protobuf API (`scala.meta.internal.semanticdb`)
- `com.lihaoyi:upickle:4.2.1` — JSON for MCP wire protocol
- `org.scalameta:munit:1.2.3` — tests
- `semanticdbEnabled := true` — project emits its own SemanticDB; analyzer dogfoods on this repo.

## Layout
Three Mill modules (layer per module), package base `com.github.mercurievv.scalasemantic`:
```
core/      …​.semanticdb   SemanticIndex — loads/indexes *.semanticdb (no JSON, no MCP)
analysis/  …​.analysis, …​.model   Analyzer + upickle result models; dependsOn core
mcp/       …​.mcp, Main    stdio JSON-RPC server + entrypoint; dependsOn analysis (test->test too)
```
SemanticDB is emitted per module under `out/<module>/compile.dest/classes/META-INF/semanticdb/**`.
Dogfood tests load `SemanticIndex.fromProject(".")` (repo root) so they see every module's output;
unforked tests run with cwd = repo root. `mcp` test-depends on `analysis` so fixtures compile first.

## Architecture
3 layers: **MCP stdio JSON-RPC** → **analysis engine** → **SemanticIndex**.
- No Scala MCP SDK exists — JSON-RPC is hand-rolled over stdin/stdout with upickle.
- Signature rendering is a custom `Type`/`Signature` printer; implicit params detected via `SymbolInformation.Property.IMPLICIT` bitmask.
- Call graph: edges from `SymbolOccurrence` references within a method's definition range; BFS for path-find.

## MCP tools (target surface)
find-usages, method-signature, class-hierarchy, resolve-implicits, trait-vs-local-members,
type-at-position, cross-file-refs, find-overloads, trace-implicit-chain, call-graph-path,
rename-plan, move-plan, extract-method-plan.

## Conventions
- Symbol strings follow SemanticDB grammar (descriptors end in `#` type, `.` term, `/` package, `(...)` method disambig).
- Result types are `upickle` case classes with derived `ReadWriter`.
- Validate every feature by dogfooding against this repo's own SemanticDB.
- New scripts under `scripts/` should preferably be written as scala-cli scripts (`.sc`, `#!/usr/bin/env scala-cli`
  shebang, `//> using scala 3.8.4`), not shell — see `scripts/smoke-test-mill.sc` or `scripts/compare_grep.sc` for
  the house style. Plain `.sh` remains fine for thin wrappers/glue that only shell out to other tools.

## LLM task design
When an LLM/agent creates tasks, examples, fixtures, or test scenarios for this project:
- Make them representative of real Scala codebases and workflows, not toy-only cases.
- Write for Scala coders as the primary audience; use idiomatic Scala terminology and examples.
- Prefer tasks that save LLM tokens/context by producing compact, high-signal semantic answers.
- For tests, use the best available practices for this repo: dogfood against SemanticDB output,
  cover realistic edge cases, and keep assertions focused on externally useful behavior.

## Claude agentic task flow
When using Claude/agentic workflows to organize project tasks:
- Use `.claude/skills/task-splitting-evaluation/SKILL.md` before implementation to evaluate open
  tasks, split broad work into bounded subtasks, choose the preferred LLM/model for every executable
  leaf, and write those notes back into task comments.
- The flow is implemented by `.claude/agents/task-prioritizer.md` and
  `.claude/agents/task-plan-architect.md`.

## Build / test
```
./mill __.compile   # regenerates SemanticDB for all modules
./mill __.test
./mill prePush      # clean; checkFormatAll; compatGoldenAll; test all 4 modules; stainlessVerify
./mill mcp.runMain com.github.mercurievv.scalasemantic.mcpServer <root>  # start the server
```
`scalafixAll` is NOT in `prePush` right now — no Mill 1.x build of `mill-scalafix` exists yet
(see `docs/MILL_MIGRATION.md` §2). `build.sbt`/`project/` are gone; do not use `sbt` at all —
the CI `publish` job (`sbt-ci-release`) and `mutation.yml` (stryker4s) are both gated `if: false`
pending a Mill 1.x path for each.

Agent worktrees live at `./.worktrees/<branch>`. See `scripts/worktree-new.sh`.

For worktree PR flow, use `./tree2m [--remote origin] [--base master] [--title TITLE] [--body BODY] [--draft] <branch> <commit-message>`.
`<branch>` is the new branch name, `<commit-message>` is passed to `git commit -m`, `--title`
overrides the PR title, `--body` overrides the PR body, `--base` selects the target branch, and
`--draft` opens a draft PR. The script creates the branch, stages all files, commits, pushes, waits
for the push-triggered CI workflow to pass, and merges the PR with squash merge using a strict
fail-fast chain. If the branch or PR already exists, `tree2m` reuses it: it adds another commit to
the existing branch, pushes, and reuses the existing PR instead of creating a duplicate. Do not pre-run
Mill checks (`compile`, `test`, `scalafmt`) before `tree2m`; the repository pre-push hook already
runs them. When an LLM/agent is asked to run `tree2m`, it should generate an appropriate branch
name, commit message, PR title, and PR body from the current change instead of asking the user for
those parameters.

## Releasing
PR-based: branch protection on master, squash-merge PRs with **Conventional-Commit titles**
(`feat:`, `fix:`, `perf:`, `feat(scope)!:` for breaking; `docs/refactor/test/chore/ci/build/style`
for everything else). Version is the git tag (`vX.Y.Z`); pushing a tag publishes to Maven Central
via `sbt-ci-release`.

Cut a release on demand: `scripts/bump-{fix,minor,major}.sh` tags the latest `origin/master` commit
and pushes the tag (no opt-in — pushing is unconditional). The tag push drives CI: Maven publish +
the GitHub Release.

**Release notes are GENERATED — never hand-edit them.** [`scripts/changelog.sh`](scripts/changelog.sh)
keeps only user-facing Conventional-Commit types (`feat`/`fix`/`perf` + breaking) and OMITS
docs/refactor/test/chore; it feeds both the GitHub Release body and `docs/RELEASE_NOTES.md` (rebuilt
by [`scripts/gen-release-notes.sh`](scripts/gen-release-notes.sh) at site-build time, hence
gitignored). So note quality == PR-title quality — write good titles. Process:
[`docs/RELEASING.md`](docs/RELEASING.md).

## Scala Code Rules
@SCALA_SEMANTIC_RULES.md

@scala-rules.md
