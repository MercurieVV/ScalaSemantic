# sbt build inventory

Baseline for evaluating whether Mill can replace the current sbt build.

## Version and plugin inventory

| Capability | Where configured | Why it matters for migration |
|---|---|---|
| sbt version 2.0.1 | `project/build.properties` | Mill migration must replace an sbt 2 meta-build, not the sbt 1 plugin ecosystem defaults. Some current choices exist specifically because of sbt 2 compatibility gaps. |
| Scala 3.8.4 main build | `build.sbt` (`ThisBuild / scalaVersion`) | Core, pc, analysis, mcp, and root default to the latest Scala 3 line. Mill must compile and test this axis while preserving SemanticDB output. |
| sbt meta-build conflict suppression | `project/plugins.sbt` (`ThisBuild / conflictWarning := ConflictWarning.disable`) | Allows sbt 2's Scala 3 meta-build to tolerate plugins that still pull Scala 2.13 artifacts. Mill has a different plugin/dependency model, so this workaround may disappear but equivalent plugin compatibility must be checked. |
| sbt-scalafix 0.14.7 | `project/plugins.sbt` | Provides `semanticdbEnabled`, `semanticdbVersion := scalafixSemanticdb.revision`, `scalafixDependencies`, and the `scalafixAll --check` gate. Mill replacement needs scalafix plus SemanticDB emission. |
| sbt-scalafmt 2.6.1 | `project/plugins.sbt` | Provides `scalafmtCheckAll`, used in CI and `prePush`. Mill needs an equivalent formatting check across the same source roots. |
| sbt-wartremover 3.6.0 | `project/plugins.sbt` | Enforces compile-time wart policy, with module-specific disabling and relaxed test settings. Mill replacement needs either wartremover integration or an explicit decision to drop/change this quality gate. |
| sbt-ci-release 1.11.2 | `project/plugins.sbt`, `build.sbt`, `.github/workflows/ci.yml` | Handles dynver, signing, Sonatype Central upload, and tag-driven release via `sbt ci-release`. This is the largest sbt-specific release dependency. |
| sbt-assembly 2.3.1 | `project/plugins.sbt`, `mcp` settings in `build.sbt` | Builds `scalasemantic-mcp.jar` with a custom merge strategy for compiler/scalameta/protobuf classpath conflicts. Mill must reproduce the fat jar and merge semantics. |
| sbt-buildinfo 0.13.1 | `project/plugins.sbt`, `mcp` settings in `build.sbt` | Generates runtime build info containing the dynver-derived version for MCP `serverInfo.version`. Mill needs equivalent generated source support. |
| ProGuard library 7.9.1 in meta-build | `project/plugins.sbt`, custom `proguard` task in `build.sbt` | The custom shrink task constructs and runs ProGuard directly from sbt. Mill would need a custom task or separate script with the same class keep rules. |
| gated sbt-stryker4s snapshot | `project/plugins.sbt`, `.github/workflows/mutation.yml`, `stryker4s.conf` | Mutation testing depends on a locally published snapshot (`0.0.0+1-2c0f5bd7-SNAPSHOT`) gated by `-Dstryker=true`/`STRYKER`. This is sbt-specific and currently works around published plugin ABI incompatibility. |
| SLF4J NOP binding in meta-build | `project/plugins.sbt`, `project/build.sbt`, root `initialize` | Suppresses sbt boot/logger noise, including copying the NOP jar into the sbt boot directory. This is an sbt-only workaround and should not be carried over unless Mill has analogous noise. |
| disabled sbt-stainless plugin | `project/plugins.sbt`, `analysis/lib/stainless-library.jar`, `stainlessVerify` task | Stainless verification is intentionally not an sbt plugin because the available plugin is sbt 1.x only. Mill migration should keep the standalone tool invocation unless a Mill-native integration is chosen. |

## Module and dependency graph

| Capability | Where configured | Why it matters for migration |
|---|---|---|
| `core` module | `build.sbt`, `core/` | Loads/indexes SemanticDB and depends on scalameta, semanticdb-shared, and munit. It uses shared compiler flags, SemanticDB, and wart policy. |
| `pc` module | `build.sbt`, `pc/` | Depends on `core`, disables WartRemover, and forks tests so `java.class.path` is the real module test classpath for the presentation compiler. Mill must preserve forked test semantics. |
| `analysis` module | `build.sbt`, `analysis/` | Depends on `core` and `pc`, includes upickle, refined, munit, munit-scalacheck, and an unmanaged Stainless jar. Owns the custom `stainlessVerify` task. |
| `mcp` module | `build.sbt`, `mcp/` | Depends on `analysis` with `compile->compile;test->test`, enabling MCP tests to dogfood analysis fixtures and emitted SemanticDB. Mill must preserve cross-module test-fixture visibility. |
| `sbtPlugin` module | `build.sbt`, `sbt-plugin/` | Real sbt plugin cross-built for Scala 2.12/sbt 1.11.6 and Scala 3/sbt 2.0.1. It reuses `project/ScalaSemanticConfigMerger.scala` as an unmanaged source and embeds launcher scripts as resources. This module may remain sbt-specific even if the main build moves. |
| `compatFixtures` module | `build.sbt`, `compat-fixtures/` | Non-published fixture module cross-compiled for Scala 2.13.16 and 3.3.4; emits SemanticDB and copies golden outputs into `analysis/src/test/resources/compat/scala-*`. Mill must reproduce cross-version fixture compilation and resource copying. |
| `docs` module | `build.sbt`, `mdoc-docs/` | Non-published mdoc wrapper project on Scala 3.3.4. It forks from repo root and renders `docs/` into `website/docs/` via `sbt docs/run`. Mill must preserve the working directory and version variable behavior. |
| `root` aggregate | `build.sbt` | Aggregates `core`, `pc`, `analysis`, `mcp`, and `sbtPlugin`, but not `compatFixtures` or `docs`. It also skips publishing and applies the sbt boot NOP logger workaround. |

## Shared settings, tasks, and aliases

| Capability | Where configured | Why it matters for migration |
|---|---|---|
| shared warnings-as-errors | `commonSettings` in `build.sbt` | Scala 3 uses `-Werror`, `-Wunused:all`, and `-Wconf:msg=.*unused.*:e`; Scala 2.12 plugin axis uses `-Xfatal-warnings` and specific `-Ywarn-unused:*` flags. Mill must preserve version-sensitive scalac options. |
| SemanticDB emission | `commonSettings`, `compatFixtures` settings in `build.sbt` | `semanticdbEnabled := true` and `semanticdbVersion := scalafixSemanticdb.revision` are central to dogfooding and scalafix. The emitted layout must remain discoverable by `SemanticIndex.fromProject(".")`. |
| WartRemover policy | `commonSettings`, module `.disablePlugins(wartremover.WartRemover)` | Compile sources get strict wart errors plus warnings for mutability/unsafe constructs; tests remove the strict wart traversers. Mill must support equivalent per-configuration and per-module policy. |
| dependency constants | `build.sbt` | Central versions: munit 1.3.3, munit-scalacheck 1.3.0, upickle 4.4.3, refined 0.11.3, slf4j-nop 1.7.36, scalameta/semanticdb-shared 4.17.0, scala3-presentation-compiler 3.8.4, mdoc 2.9.0. Mill should keep these centralized. |
| `stainlessVerify` task | `build.sbt`, `scripts/stainless-verify.sh`, `.github/workflows/ci.yml` | Runs the standalone Stainless verifier over production kernels and fails on invalid VCs. CI sets `STAINLESS_TIMEOUT=30`; local `prePush` uses script defaults. |
| `compatGolden` task | `build.sbt` | Compiles `compatFixtures`, reads `Compile / semanticdbTargetRoot / META-INF / semanticdb`, deletes the target golden directory, and copies emitted SemanticDB resources. This depends on sbt's SemanticDB task keys and cross build state. |
| `compatGoldenAll` alias | `build.sbt`, `.github/workflows/ci.yml` | Expands to `++2.13.16 compatFixtures/compatGolden; ++3.3.4 compatFixtures/compatGolden`. Mill replacement needs an ordered multi-version command. |
| `mcpLauncher` task | `build.sbt` | Writes a dev launcher under module `target/` using the sbt runtime classpath converted from virtual file refs. Mill needs equivalent runtime classpath materialization. |
| `mcpClientConfig` input task | `build.sbt` | Installs `scripts/scalasemantic-mcp.sh`, prefetches the release jar, and writes/merges MCP client configs for Claude, Codex, Gemini, Cline, Roo, Continue, and Antigravity via `ScalaSemanticConfigMerger`. This is a custom side-effecting setup task. |
| `proguard` task | `build.sbt` | Runs after assembly, writes `target/proguard/scalasemantic-mcp-shrunk.jar`, and applies detailed keep/merge assumptions. Mill must either reimplement it or replace it with a script. |
| `testShrunk` task | `build.sbt` | Builds a custom forked JVM classpath using the shrunk jar plus test classpaths and runs selected JUnit suites. Mill needs equivalent classpath surgery if the shrink test remains. |
| `prePush` alias | `build.sbt`, `scripts/agent-run.sh` | Runs `clean; scalafmtCheckAll; scalafixAll --check; Test/testOnly *; stainlessVerify`. It is verify-only and relies on sbt 2 behavior where plain `test` is cached `testQuick`. |

## Release, publishing, and CI touchpoints

| Capability | Where configured | Why it matters for migration |
|---|---|---|
| Maven metadata and dynver release model | `build.sbt`, `scripts/config.sh`, `scripts/bump-version.sh`, `.github/workflows/ci.yml` | Version comes from `vX.Y.Z` tags. `bump-version.sh` tags latest remote `master`; CI publishes via `sbt ci-release`. Mill must replace dynver/versioning, signing, and Sonatype upload. |
| Sonatype Central metadata | `build.sbt`, `scripts/config.sh`, `.github/workflows/ci.yml` | Organization, developers, SCM, license, `versionScheme`, Central host, and secrets are all wired for `ci-release`. Mill publishing must preserve Maven coordinates and metadata. |
| CI build job | `.github/workflows/ci.yml` | Runs `sbt scalafmtCheckAll`, `sbt compatGoldenAll`, `sbt +sbtPlugin/compile`, and `sbt test` on Java 21 with sbt cache. Mill migration needs replacement commands and cache strategy. |
| CI verification job | `.github/workflows/ci.yml` | Runs `sbt stainlessVerify` after caching the standalone Stainless tool. Could become a direct script call, but currently the public CI entrypoint is an sbt task. |
| docs site job | `.github/workflows/ci.yml`, `scripts/gen-release-notes.sh` | On `master`, generates release notes, runs `sbt docs/run`, then builds Docusaurus. Mill must preserve docs rendering before `npm run build`. |
| publish job | `.github/workflows/ci.yml` | On release tags or manual workflow dispatch, imports GPG secrets and runs `sbt ci-release`. This has no direct portable equivalent without replacing `sbt-ci-release`. |
| GitHub Release job | `.github/workflows/ci.yml` | On release tags, runs `sbt "mcp/assembly"`, finds `scalasemantic-mcp.jar`, and attaches it to the GitHub Release with generated notes. Mill must build the same jar asset. |
| mutation workflow | `.github/workflows/mutation.yml`, `scripts/run-stryker.sh`, `scripts/mutation-summary.sh` | PRs touching `analysis/**` build a local stryker4s snapshot on cache miss, run `sbt compile`, then `sbt "analysis/stryker"` with `STRYKER=true`, commit the JSON report, and enforce the configured score gate. This is entirely sbt-plugin based. |
| Scala Steward workflow | `.github/workflows/scala-steward.yml` | Runs Scala Steward, which is normally sbt-aware. A Mill migration changes dependency-update tooling assumptions. |
| release helper scripts | `scripts/bump-fix.sh`, `scripts/bump-minor.sh`, `scripts/bump-major.sh`, `scripts/bump-version.sh`, `scripts/retry-last-tag.sh`, `scripts/check-push-workflow.sh`, `scripts/setup-gh-repo.sh`, `scripts/changelog.sh`, `scripts/gen-release-notes.sh`, `scripts/update-doc-versions.sh` | These scripts mostly operate on git/GitHub/release metadata, but comments and workflow checks assume tag-driven `sbt-ci-release` and the `CI` workflow. |
| agent helper scripts | `scripts/agent-run.sh`, `scripts/agent-ship.sh`, `scripts/agent-merge.sh`, `scripts/worktree-new.sh`, `scripts/worktree-diff.sh`, `scripts/commit-push.sh`, `scripts/agent-status.sh` | `agent-run.sh` explicitly instructs workers to run `sbt --error compile`, `sbt --error test`, optional `sbt --error prePush`, then `tree2m`. Mill migration would need orchestration prompt/script updates. |
| install and launcher scripts | `scripts/install.sh`, `scripts/scalasemantic-mcp.sh`, `scripts/scalasemantic-mcp.ps1` | Runtime launch path intentionally needs Java/coursier and no sbt. These are mostly build-tool independent, except `mcpClientConfig` and `sbtPlugin` package/copy them. |

## sbt calls found under scripts and workflows

| File | sbt touchpoint | Migration note |
|---|---|---|
| `.github/workflows/ci.yml` | `sbt scalafmtCheckAll`; `sbt compatGoldenAll`; `sbt +sbtPlugin/compile`; `sbt test`; `sbt stainlessVerify`; `sbt docs/run`; `sbt ci-release`; `sbt "mcp/assembly"` | Main CI, docs, publish, and release asset flow. |
| `.github/workflows/mutation.yml` | `sbt sbtPlugin3/publishLocal` in a stryker4s clone; `sbt compile`; `sbt "analysis/stryker"` | Mutation testing depends on both upstream stryker4s' sbt build and this repo's sbt plugin. |
| `scripts/agent-run.sh` | Prompted worker commands: `sbt --error compile`, `sbt --error test`, optional `sbt --error prePush` | Agent lifecycle must be rewritten if sbt is removed. |
| `scripts/run-stryker.sh` | `STRYKER=1 sbt -Dstryker=true "analysis/stryker"` | Local mutation runner is sbt-specific. |
| `scripts/mutation-summary.sh` | Help text references `sbt -Dstryker=true "analysis/stryker"` | Documentation/help update only. |
| `scripts/config.sh`, `scripts/bump-version.sh`, `scripts/setup-gh-repo.sh`, `scripts/check-push-workflow.sh`, `scripts/gen-release-notes.sh`, `scripts/update-doc-versions.sh` | Comments/config assume `sbt-ci-release`, `sbt docs/run`, or `CI` workflow behavior | Mostly indirect migration edits. |
| `scripts/scalasemantic-mcp.sh`, `scripts/scalasemantic-mcp.ps1` | Explicitly state no sbt is needed | No replacement required for runtime launcher behavior. |

## sbt-specific or high-risk migration items

- `sbt-ci-release` is the hardest release dependency: it combines dynver, signing, Sonatype Central upload, and publish lifecycle conventions.
- `sbtPlugin` is inherently sbt-specific and cross-builds against sbt 1 and sbt 2. A Mill build can build a Scala artifact, but publishing/testing an sbt plugin still needs a deliberate compatibility plan.
- SemanticDB output paths and `semanticdbTargetRoot` are load-bearing for dogfood tests and `compatGolden`; Mill must prove that emitted files land where `SemanticIndex.fromProject(".")` can find them or the loader/tests must change.
- `mcp` test-depends on `analysis` tests with `compile->compile;test->test`; Mill must preserve this exact fixture visibility.
- `pc / Test / fork := true` is required because presentation-compiler tests inspect `java.class.path`; non-forked or differently forked Mill tests could fail subtly.
- `assembly` plus the custom merge strategy and ProGuard shrink/test tasks must be reproduced if the release jar contract remains unchanged.
- `sbt-stryker4s` is not a portable capability. The current setup also depends on a local snapshot because the published plugin is incompatible with current sbt 2.
- `scalafixAll --check`, wartremover configuration, and version-sensitive compiler flags are quality gates, not cosmetic settings.
- `docs/run` is not the sbt-mdoc plugin; it is a custom wrapper project with forked cwd and a generated version variable. Migration should model that behavior directly.

## Verification checklist

- Every plugin in `project/plugins.sbt` is represented above: scalafix, scalafmt, wartremover, ci-release, assembly, buildinfo, conditional stryker4s, ProGuard library, SLF4J NOP, and the disabled Stainless plugin note.
- Every `*.sbt` file is accounted for: `build.sbt`, `project/plugins.sbt`, and `project/build.sbt`.
- `project/build.properties` is accounted for.
- All workflow `sbt` calls are accounted for in CI and mutation workflows.
- Script references to `sbt` under `scripts/` are accounted for, including direct invocations and orchestration/help-text references.
