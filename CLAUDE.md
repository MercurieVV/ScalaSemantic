# ScalaSemantic

MCP server doing deep semantic analysis on Scala projects via SemanticDB — capabilities beyond standard LSP/Metals.

## Stack
- Scala 3.8.4, sbt 2.0.0
- `org.scalameta:scalameta:4.13.9` — SemanticDB protobuf API (`scala.meta.internal.semanticdb`)
- `com.lihaoyi:upickle:4.2.1` — JSON for MCP wire protocol
- `org.scalameta:munit:1.2.3` — tests
- `semanticdbEnabled := true` — project emits its own SemanticDB; analyzer dogfoods on this repo.

## Layout
Three sbt modules (layer per module), package base `com.github.mercurievv.scalasemantic`:
```
core/      …​.semanticdb   SemanticIndex — loads/indexes *.semanticdb (no JSON, no MCP)
analysis/  …​.analysis, …​.model   Analyzer + upickle result models; dependsOn core
mcp/       …​.mcp, Main    stdio JSON-RPC server + entrypoint; dependsOn analysis (test->test too)
```
SemanticDB is emitted per module under `<module>/target/out/.../meta/META-INF/semanticdb/**`.
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

## Build / test
```
sbt compile      # regenerates SemanticDB for all modules
sbt test
sbt prePush      # command alias: clean; scalafmtAll; scalafixAll; Test/testOnly * (aggregates all modules)
sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"  # start the server
```

For worktree PR flow, use `./treepr [--remote origin] [--base master] [--title TITLE] [--body BODY] [--draft] <branch> <commit-message>`.
`<branch>` is the new branch name, `<commit-message>` is passed to `git commit -m`, `--title`
overrides the PR title, `--body` overrides the PR body, `--base` selects the target branch, and
`--draft` opens a draft PR. The script creates the branch, stages all files, commits, pushes, and
opens the PR using a strict fail-fast chain. Do not pre-run sbt checks (`compile`, `test`,
`scalafmt`, `scalafix`) before `treepr`; the repository pre-push hook already runs them. When an
LLM/agent is asked to run `treepr`, it should generate an appropriate branch name, commit message,
PR title, and PR body from the current change instead of asking the user for those parameters.

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
