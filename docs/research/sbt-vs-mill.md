# sbt vs Mill: Migration Assessment for ScalaSemantic

_Issue #108 — "Because mill is more scala code, so ScalaSemantic could be applied. Could it save all used sbt functionality?"_

---

## 1. What sbt Features This Project Uses

### 1.1 Plugins

| Plugin | Purpose |
|--------|---------|
| `sbt-ci-release` (1.11.2) | Tag-triggered publish to Sonatype Central Portal — derives version from `sbt-dynver`, signs artifacts with `sbt-pgp`, uploads via sbt-sonatype. |
| `sbt-dynver` | (pulled in by ci-release) Version derived from git tags (`vX.Y.Z` → `X.Y.Z`). |
| `sbt-scalafmt` (2.6.1) | `scalafmtAll` / `scalafmtCheckAll` across all modules. |
| `sbt-scalafix` (0.14.7) | `scalafixAll` / `scalafixAll --check`; `OrganizeImports` rule, `typelevel-scalafix`. |
| `sbt-wartremover` (3.6.0) | Wart checks per module, per scope (compile vs. test have different error sets). |
| `sbt-assembly` (2.3.1) | Fat-jar `scalasemantic-mcp.jar` attached to GitHub Releases for `java -jar` usage. |
| `sbt-buildinfo` (0.13.1) | Generates `BuildInfo.version` from dynver so the server reports its real version at runtime. |
| `sbt-git` (implicit, via SbtGit import) | `useReadableConsoleGit`, `gitDescribedVersion`, `gitUncommittedChanges`. |
| ProGuard (library in meta-build) | `proguard` task shrinks the fat jar; `testShrunk` runs tests against the shrunk artifact. |
| Stryker4s (snapshot, optional) | Mutation testing (`analysis/stryker`); gated by `-Dstryker=true` flag. |
| `SbtPlugin` (built-in) | Cross-builds `sbt-plugin` for sbt 1.x (Scala 2.12) and sbt 2.x (Scala 3). |

### 1.2 Multi-Module Structure

Five published/utility modules aggregated under `root`:

```
root
├── core              scalasemantic-core        (SemanticDB index)
├── pc                scalasemantic-pc          (presentation-compiler backend; forked tests)
├── analysis          scalasemantic-analysis    (query engine + upickle models)
├── mcp               scalasemantic-mcp         (stdio JSON-RPC server; fat-jar entry)
└── sbt-plugin        sbt-scalasemantic-mcp     (sbt plugin, cross-built 2.12 + Scala 3)

Not aggregated (separate version/classpath):
├── compat-fixtures   (cross-compiled SemanticDB golden fixtures, 2.13 + 3.3.4)
└── mdoc-docs         (documentation site rendered with mdoc library, pinned to Scala 3.3.4)
```

Inter-module dependencies:
- `pc` dependsOn `core`
- `analysis` dependsOn `core, pc`
- `mcp` dependsOn `analysis` (`compile->compile; test->test`)
- `sbt-plugin` references `project/ScalaSemanticConfigMerger.scala` via `unmanagedSources`

### 1.3 Custom Tasks and Aliases

| Task / alias | Defined in | What it does |
|---|---|---|
| `prePush` | command alias | `clean; scalafmtCheckAll; scalafixAll --check; Test/testOnly *; stainlessVerify` |
| `compatGoldenAll` | command alias | `++<ver> compatFixtures/compatGolden` for each version in `compatScalaVersions` |
| `mcpLauncher` | `InputKey` in `mcp` | Writes a `target/scalasemantic-mcp` dev shell script with the full classpath |
| `mcpClientConfig` | `InputKey` in `mcp` | Installs the launcher and merges MCP JSON/TOML/YAML configs for IDE clients |
| `stainlessVerify` | `TaskKey` in `analysis` | Shells out to `scripts/stainless-verify.sh` (standalone Stainless tool + Z3) |
| `proguard` | `TaskKey` in `mcp` | Runs ProGuard programmatically (proguard-base jar in meta-build classpath) |
| `testShrunk` | `TaskKey` in `mcp` | Runs three named test suites against the ProGuard-shrunk jar |
| `compatGolden` | `TaskKey` in `compatFixtures` | Copies emitted `*.semanticdb` into versioned golden test resources |
| `ci-release` | from sbt-ci-release | Signs + uploads all modules on tag push |

### 1.4 Noteworthy Patterns

- **Unmanaged jar**: `analysis/lib/stainless-library.jar` checked in; sbt auto-includes it.
- **Resource generator**: `sbt-plugin` generates launcher scripts (`scalasemantic-mcp.sh`, `.ps1`) into `resourceManaged` at compile time.
- **`fork := true` in `pc/Test`**: required so the forked JVM's classpath includes scala-library for the presentation compiler.
- **Cross-build for sbt plugin**: `crossScalaVersions := Seq("2.12.21", "3.8.4")` with per-axis `pluginCrossBuild / sbtVersion`.
- **Conditional plugin loading**: Stryker4s plugin is only added when `-Dstryker=true` is set — prevents build failures in environments without the locally-published snapshot.
- **`semanticdbEnabled := true`** everywhere: the project eats its own SemanticDB output (dogfooding).
- **`scalafixSemanticdb.revision`** sets `semanticdbVersion` to keep scalafix and SemanticDB in sync.
- **`versionScheme := "early-semver"`**: used by sbt-version-policy / Sonatype.
- **Meta-build (`project/`)**: contains `ScalaSemanticConfigMerger.scala` shared between the meta-build and `sbt-plugin` via `unmanagedSources`.

---

## 2. Mill Equivalents

### 2.1 Core Build Concepts

| sbt concept | Mill equivalent | Status |
|---|---|---|
| `build.sbt` + `project/` | `build.mill` (pure Scala) | Direct equivalent |
| `lazy val module = project in file(...)` | `object module extends ScalaModule` | Direct equivalent |
| `dependsOn(other)` | `def moduleDeps = Seq(other)` | Direct equivalent |
| `ThisBuild / scalaVersion` | `trait CommonModule extends ScalaModule { def scalaVersion = "3.8.4" }` | Idiomatic Mill |
| `Test / fork := true` | `def forkArgs` / `def forkEnv` | Available |
| `unmanagedJars` (lib dir) | `def unmanagedClasspath` override | Available |
| `crossScalaVersions` + cross-build | `Cross[MyModule](versions*)` | Available |
| Command aliases (`addCommandAlias`) | `def myCommand = T.command { ... }` | Available |
| `Compile / resourceGenerators` | `def resources = T { ... }` | Available |
| `publish / skip := true` | No `PublishModule` mixin | Available |
| `semanticdbEnabled := true` | `def scalacOptions` + `-Xsemanticdb` | Manual; no auto-plugin equivalent |
| `semanticdbVersion` / scalafix sync | Manual `scalacOptions` | Manual |

### 2.2 Plugin Ecosystem

| sbt plugin | Mill equivalent | Gap |
|---|---|---|
| `sbt-scalafmt` | `mill-contrib-scalafmt` | Available; slightly different CLI surface. |
| `sbt-scalafix` | No official Mill scalafix plugin | **Gap**: the community `mill-scalafix` project exists (https://github.com/ckipp01/mill-scalafix) but is not maintained at the same pace. OrganizeImports and typelevel-scalafix rules would need verification. |
| `sbt-wartremover` | No official Mill wartremover plugin | **Gap**: wartremover ships a compiler plugin; Mill can load it via `def scalacPluginIvyDeps`. The per-scope (compile vs. test) wart customisation needs manual `def scalacOptions` per module, unlike the sbt DSL helpers (`wartremoverErrors`, `wartremoverWarnings`). |
| `sbt-ci-release` | No Mill equivalent | **Significant gap**: `sbt-ci-release` wraps dynver + sbt-pgp + sbt-sonatype in one plugin. Mill has `mill-vcs-version` (dynver analog) and `mill.scalalib.publish.SonatypePublisher`, but there is no `ci-release`-style one-command CI publish. The Sonatype Central Portal upload path (`central.sonatype.com`) requires separate wiring. |
| `sbt-dynver` | `mill-vcs-version` | Available; similar semantics. |
| `sbt-assembly` | `mill-assembly` (contrib) | Available; same shadow-jar approach. |
| `sbt-buildinfo` | `mill-contrib-buildinfo` | Available. |
| `sbt-git` | No direct equivalent | Minor; `git` calls can be inlined. |
| `SbtPlugin` (cross sbt 1+2) | No equivalent — Mill is not an sbt plugin host | **Fundamental incompatibility**: the `sbt-plugin` subproject produces an sbt plugin (`.sbt` files, cross-built for sbt 1.x and sbt 2.x). Mill cannot produce or consume sbt plugins. The sbt plugin sub-project would have to remain in sbt regardless, or be rewritten as a Mill plugin if/when Mill adoption warrants it. |
| ProGuard (meta-build library) | `def proguard = T { ... }` + `ivy"..."` dependency | Available; Mill can add ProGuard as a module dependency and call it from a task. |
| Stryker4s | `mill-stryker4s` is in early development | **Gap**: the snapshot situation mirrors sbt — immature. |

### 2.3 Features That Need Manual Reimplementation

1. **SemanticDB integration**: sbt-scalafix auto-injects `-Xsemanticdb` and the SemanticDB compiler plugin. In Mill this must be done explicitly per module with the right `scalacOptions` and plugin dep.
2. **Cross-version wart rules per scope**: sbt-wartremover's DSL (`wartremoverErrors`, `wartremoverWarnings`, per-scope removal via `--=`) is sugar over compiler plugin args. In Mill, the same effect requires explicit `scalacOptions` overrides for each module + scope — verbose but achievable.
3. **`sbt-ci-release` pipeline**: The CI workflow's `sbt ci-release` invocation bundles signing + upload in one command. Mill would need separate steps: version derivation (`mill-vcs-version`), signing (sbt-pgp's GPG logic is not reusable; mill's `PublishModule` has its own GPG support), and upload.
4. **`project/ScalaSemanticConfigMerger.scala` shared between meta-build and plugin**: In sbt, `project/` is the meta-build and its Scala files are on the plugin's classpath. In Mill, the equivalent is a `millbuild/` module; sharing source between it and the plugin module needs explicit `moduleDeps` or a utility object.
5. **`compatGoldenAll` iterating over Scala versions**: The sbt alias loops `++<ver> compatFixtures/compatGolden` for each version. In Mill this would be a `Cross` module with a `T.command` task that delegates to each cross-version instance — achievable but less ergonomic than the alias.

---

## 3. The "ScalaSemantic Can Analyze Mill Builds" Angle

### 3.1 Why It Is a Real Advantage

Mill builds (`build.mill`) are **valid Scala 3 source files**. If the Mill build itself is compiled with SemanticDB enabled (which Mill supports via `--meta-build` options or a wrapper project), ScalaSemantic's full tool suite applies:

- `document_outline` can map all module definitions and task declarations.
- `find_usages` can find every place a shared trait (e.g., `CommonModule`) is mixed in.
- `class_hierarchy` shows the full mixin chain of a module.
- `rename_plan` gives compiler-precise edits to rename a task or setting key.
- `call_path` can trace which task chain leads to a given operation.
- `annotated_source` exposes inferred types for all `T { ... }` task bodies.

None of this is possible with sbt's `build.sbt`, which is a restricted DSL preprocessed outside the Scala compiler — SemanticDB is not emitted for `build.sbt` (only for `project/*.scala`). The `project/ScalaSemanticConfigMerger.scala` meta-build file IS indexable (it's plain Scala), but the main `build.sbt` body is not.

### 3.2 What This Actually Buys

| Capability | sbt (build.sbt) | Mill (build.mill) |
|---|---|---|
| Navigate task definitions with `document_outline` | Only `project/*.scala` | Full build file |
| `find_usages` on a shared module trait | `project/*.scala` only | Full build file |
| Rename a setting key safely | Only `project/*.scala` | Full build file |
| `call_path` across tasks | Not possible | Possible (tasks are methods) |
| Type-at-position on task body | Not possible | Possible |

This is a genuine advantage for a project whose core value proposition is deep Scala code navigation. A Mill build would allow ScalaSemantic to analyze its own build infrastructure — a compelling dogfooding story. However, the benefit is primarily **developer experience during build authoring and refactoring**, not runtime correctness.

### 3.3 Caveats

- Mill tasks use macros (`T { ... }`, `T.command { ... }`) that expand at compile time. SemanticDB records the post-macro tree, so some positions are less intuitive.
- SemanticDB for the Mill meta-build requires a separate compile step or Mill's `--watch` mode to regenerate on changes.
- The advantage grows with build complexity. ScalaSemantic's current build is already at ~700 lines of `build.sbt` + significant `project/` Scala. At this size, navigability of a `build.mill` file would be meaningfully better.

---

## 4. Estimated Migration Effort

| Area | Effort |
|---|---|
| Core multi-module structure (`core`, `pc`, `analysis`, `mcp`) | 1–2 days |
| Custom tasks (`mcpLauncher`, `mcpClientConfig`, `stainlessVerify`, `proguard`, `testShrunk`, `compatGolden`) | 1–2 days |
| Plugin replacements (scalafmt, scalafix, wartremover, assembly, buildinfo) | 1 day |
| SemanticDB + scalafix integration (manual vs. auto-plugin) | 0.5 day |
| `sbt-ci-release` → Mill publish + VCS version + GPG | 1–2 days |
| `sbt-plugin` module (cross sbt 1+2) — **no Mill equivalent** | Blocked or kept in sbt; 0 days if kept as-is |
| `compat-fixtures` cross-compile + golden copy | 0.5 day |
| `mdoc-docs` (currently uses mdoc library directly, not sbt-mdoc) | 0.5 day |
| CI workflow rewrite (`ci.yml`, `mutation.yml`) | 0.5–1 day |
| Validation / test stabilization | 2–3 days |
| **Total** | **~8–12 developer-days** |

The estimate assumes the `sbt-plugin` subproject stays in sbt (it must — Mill cannot produce sbt plugins). If a Mill plugin were written instead, add another 5–10 days, plus ecosystem uncertainty.

---

## 5. Recommendation: Stay with sbt (for now)

**Verdict: Do not migrate at this time.**

### Reasons to stay

1. **The sbt plugin is a first-class deliverable.** `sbt-scalasemantic-mcp` must build as an sbt plugin cross-compiled for sbt 1.x and sbt 2.x. There is no Mill analog. A migration would split the build: Mill for the server modules, sbt for the plugin — which is worse than the current unified sbt build.

2. **`sbt-ci-release` has no drop-in Mill equivalent.** The Central Portal upload path and GPG signing are already working reliably. Reimplementing this in Mill would require assembling the pipeline from parts and is a maintenance risk.

3. **`sbt-scalafix` and `sbt-wartremover` are deeply integrated.** The per-scope wart customization (different error sets for compile vs. test, cross-version `scalacOptions` filtering) relies on sbt-wartremover's DSL. Reproducing this fidelity in Mill requires non-trivial per-module boilerplate.

4. **Migration cost is real; reward is limited.** 8–12 developer-days for a tooling migration that does not change the server's capabilities or user-facing behavior.

### When to reconsider

- If the `sbt-plugin` is eventually deprecated or moved to a separate repository.
- If the sbt 2.0 ecosystem stagnates and key plugins (scalafix, wartremover) stop tracking it.
- If the build file grows significantly more complex and the lack of ScalaSemantic navigability on `build.sbt` becomes a real friction point.

### Partial win available today

The ScalaSemantic-on-build-files angle is real but does not require migrating. An alternative is to make the `project/` Scala files richer (moving more logic from `build.sbt` into `project/*.scala`), which is already what `ScalaSemanticConfigMerger.scala` does. Incrementally extracting task logic into `project/` gives most of the navigability benefit with zero migration cost.
