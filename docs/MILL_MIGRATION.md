# sbt → Mill Migration Catalog

Full inventory of what the current sbt build does, so nothing is dropped when switching to Mill.
Current: **sbt 2.0.1**, Scala **3.8.4**, 5 modules + root aggregate. Sources: `build.sbt`,
`project/plugins.sbt`, `project/build.sbt`, `project/*.scala`, `.github/workflows/*`, `.githooks/*`,
`scripts/*`.

---

## Remaining work (TODO)

**Blocked on upstream (not attemptable today, revisit periodically):**
- [ ] **Publish (`ci.yml` `publish` job)** — still `sbt --batch ci-release`. No Mill 1.x build of
      `io.chris-kipp::mill-ci-release` exists (newest published is `mill0.12`). Revisit when a
      mill1.x-tagged release ships; then wire `CiReleaseModule` + `VcsVersion`, confirm Central
      Portal host support, and drop this job's sbt setup.
- [x] **scalafix (`prePush` / CI lint step)** — **UNBLOCKED, no plugin needed.** `mill-scalafix`
      itself is still dead (no mill1.x build, see §2), but `scalafixCheck` in `Common` (build.mill)
      calls `ch.epfl.scala:scalafix-interfaces` directly — a stable, Java-facing, Maven-Central-
      published embedding API that Scalafix ships *for exactly this* (IDEs/build tools without a
      native plugin; same mechanism `gradle-scalafix` and Metals use). It fetches scalafix-core
      itself via coursier at task-run time (network on first run, cached after), builds
      `ScalafixArguments` with our `.scalafix.conf` rules (`OrganizeImports` pulled from
      `org.typelevel::typelevel-scalafix:0.5.0` as tool-classpath, the rest built in), and runs in
      `ScalafixMainMode.CHECK`. Verified end-to-end: passes clean on all 4 modules, and a smoke-test
      file with a real import-order violation produced the expected fix diff and failed the task.
      Wired into `prePush` and a new CI "Check scalafix" step. Unlike stryker4s below,
      scalafix-interfaces is a normal versioned release — no locally-published snapshot, no CI risk.
- [ ] **stryker4s mutation (`mutation.yml`)** — **proven feasible via a local snapshot, not wired
      in**: `feat(mill): add Mill plugin` (stryker-mutator/stryker4s#2042) merged to `master`
      2026-06-17, queued for the still-unreleased **v0.22.0** (stryker-mutator/stryker4s#2041;
      nothing on Maven Central yet, latest release stays v0.21.0). Cloned stryker4s at the same
      commit (`2c0f5bd7`) the existing sbt snapshot already uses, ran the repo's own
      `publishMillLocal` alias, and verified `stryker4s.mill.Stryker4sModule` end-to-end in a throwaway
      Mill project (`foo.stryker` compiled, mutated, ran tests, wrote an HTML report) using
      `io.stryker-mutator::mill-stryker4s::0.0.0-TEST-SNAPSHOT` resolved straight out of
      `~/.ivy2/local` — no explicit repository config needed. **Deliberately not wired into the main
      `build.mill`**: Mill's plugin-header `mvnDeps` (unlike sbt's `if (flag) addSbtPlugin(...) else
      Seq.empty`) resolves statically at bootstrap on *every* invocation, so adding it unconditionally
      would break CI and every other dev's plain `./mill compile` the moment the local-only snapshot
      jar is missing — the same failure mode already documented for mill-scalafix. Confirmed with the
      user: correct next step is to mirror the sbt isolation (`scripts/run-stryker.sh`'s throwaway
      `.worktrees/stryker4s-<name>` clone) with a small standalone Mill build inside that clone,
      swapping sbt for Mill only in that isolated context — not yet implemented, revisit once v0.22.0
      ships (making this whole workaround moot) or if mutation testing becomes a priority sooner.

**Doable, just not done yet:**
- [x] **CI cache** — added an explicit `actions/cache@v4` step in the `build` job over
      `~/.cache/coursier` (library deps) + `~/.cache/mill/download` (the self-downloading `./mill`
      script's own binary cache — confirmed the real path by reading the `mill` launcher script,
      not `~/.mill`), keyed on `build.mill` + `.mill-version` content hash with an OS-scoped restore
      fallback.
- [x] **scala-steward.yml** — researched, no config change needed: `scala-steward-action@v2`
      doesn't parse `build.mill` itself — it delegates extraction to a companion
      `scala-steward-org/mill-plugin` loaded *into* the target repo's own Mill build, so it's
      agnostic to the `build.sc`→`build.mill` rename and to `ivy"…"` vs `mvn"…"` syntax; only the
      mill-plugin/Mill version matters, and Mill 1.x support landed in mill-plugin 0.19.0 (Oct
      2024). The action's `mill-version` input (default `1.0.6`) only bootstraps a global `mill`
      binary — its docs state it "will still respect the version specified in your repository"
      (our `.mill-version`, 1.1.7) when actually extracting/updating deps. Left the workflow
      untouched since no explicit config is needed; only real residual risk is untested until the
      scheduled run actually executes (can't run a GitHub-hosted action locally).
- [x] **`scripts/*` still reference sbt** (§10) — switched the ones that should move:
      `token-live-metrics.sh` now runs `./mill mcp.test.testOnly …` (verified working), `agent-run.sh`
      worker instructions now say `./mill __.compile` / `./mill __.test` / `./mill prePush` (the
      git hooks already run Mill — the old sbt wording was stale), `gen-release-notes.sh` comment
      now says `./mill docs.run`. Left on sbt intentionally: `run-stryker.sh` and
      `mutation-summary.sh` (mutation.yml stays sbt-only per §8/§2), `install.sh`/`bump-version.sh`/
      `config.sh` (describe the still-sbt `publish` job / sbt-ci-release semantics — accurate until
      that job ports). `scripts/scalasemantic-mcp.sh`'s build-tool detector already special-cases
      Mill for *end users* — separate scope from these repo-internal scripts.
- [x] **docs.run / mdoc** — verified end-to-end: `./mill docs.run` compiles + renders all snippets
      (0 errors, only pre-existing broken-link warnings unrelated to Mill), writes into
      `website/docs`, and output matches the committed tree exactly (`git diff --stat website/docs`
      empty).
- [x] **Docs updated for local Mill dev** (§12) — done ahead of full cutover since the git hooks
      already run Mill: `CLAUDE.md` (Stack/Layout/Build-test sections now say Mill, note
      `build.sbt`/`project/` are publish-only leftovers), `docs/project/releasing.md` (pre-push hook
      line), `docs/project/development.md` (rewritten Build & test / gotchas sections),
      `docs/getting-started/integration.md` (assembly command). `README.md` needed no change (already
      build-tool-generic). `.claude/skills/prepush-setup/` doesn't exist in this repo — it's a global
      user command (`~/.claude/commands/prepush-setup.md`) reused across sbt projects generally, out
      of scope here.
- [ ] **Final cutover** — once publish/scalafix/mutation are resolved (or accepted as permanently
      sbt-only), delete `build.sbt`, `project/*.sbt`, `project/*.scala` (already ported into
      `build.mill`, see §7), `project/build.properties`; keep `.mill-version`.

**Done:** modules/deps (§1), wartremover/assembly/BuildInfo/ProGuard/testShrunk/compat-cross-golden/
scalafix-via-scalafix-interfaces (§2–4), meta-build helper relocation (§7), git hooks (§9),
`ci.yml` build/verify/docs-site/release
jobs (§8). See inline "DONE" / "NOT ported" markers throughout for exact status per item.

---

## 1. Modules & dependency graph

| Module | dir | dependsOn | crossScala | notable |
|---|---|---|---|---|
| `core` | `core/` | — | — | scalameta 4.17.0, semanticdb-shared 4.17.0, munit |
| `pc` | `pc/` | core | — | scala3-presentation-compiler 3.8.4; **Test/fork := true**; wartremover disabled; slf4j-nop |
| `analysis` | `analysis/` | core, pc | — | upickle, refined, munit, munit-scalacheck; **unmanaged jar** `analysis/lib/stainless-library.jar` |
| `mcp` | `mcp/` | `analysis % "compile->compile;test->test"` | — | **test→test dep**; BuildInfoPlugin; two `@main` (pin `mcpServer`); assembly fat jar |
| `compat-fixtures` | `compat-fixtures/` | — | **2.13.16, 3.3.4** | wartremover disabled; publish/skip; emits golden SemanticDB |
| `docs` | `mdoc-docs/` | — (standalone) | scala 3.3.4 | forked run; mdoc **library** 2.9.0; publish/skip |
| `root` | `.` | aggregate(core,pc,analysis,mcp) | — | publish/skip |

Mill mapping:
- Each module → `object x extends ScalaModule` (+ nested `object test extends ScalaTests`).
- `moduleDeps` for compile deps; test→test dep → test module's `moduleDeps` includes the other test module.
- `compat-fixtures`, `docs` → `Cross[…]` / pinned `scalaVersion`.
- root aggregate → Mill has no aggregate; `__.compile` / `__.test` targets cover all.

---

## 2. Plugins → Mill equivalents

| sbt plugin (version) | purpose | Mill replacement | risk |
|---|---|---|---|
| sbt-scalafmt 2.6.1 | format | built-in `ScalafmtModule` — wired, `./mill mill.scalalib.scalafmt/checkFormatAll\|reformatAll __.sources` | low — DONE |
| sbt-scalafix 0.14.7 | lint/rewrite | **NOT** `com.goyeau::mill-scalafix` (confirmed broken — see below) — instead `ch.epfl.scala:scalafix-interfaces` called directly from a `Common.scalafixCheck` task | low — DONE, unblocked without the native plugin |

`mill-scalafix`: **CONFIRMED BROKEN on Mill 1.1.7** — the only published artifact is `mill-scalafix_mill0.13_3:0.5.1` (no mill1.x build exists on Maven Central); loading it throws `scala.MatchError: val <none>` unpickling `ScalafixModule`'s TASTy, and once that trait fails to load ALL of `build.mill` fails to compile (a scalac plugin classpath resolution failure, not a targeted one). Left OUT of `build.mill` entirely — but scalafix itself is NOT skipped: `scalafix-interfaces` (Scalafix's own stable embedding API, published normally on Maven Central, same mechanism `gradle-scalafix`/Metals use) is called directly instead. See the TODO entry above for the full verification story.
| sbt-wartremover 3.6.0 | wart rules | **NO Mill plugin** — add wartremover as compiler plugin dep + `-P:wartremover:…` scalacOptions by hand | **HIGH** — DONE |
| sbt-ci-release 1.11.2 | dynver + pgp + Sonatype Central | `io.chris-kipp::mill-ci-release` (`CiReleaseModule` + `VcsVersion`) | **HIGH — NOT ported**: newest published artifact is `mill-ci-release_mill0.12_2.13:0.3.0`, same no-mill1.x-build problem as scalafix (untried, but same root cause expected). `ci.yml`'s `publish` job stays on sbt/`ci-release` for now — this is why `build.sbt`/`project/` are still in the repo. |
| sbt-assembly 2.3.1 | fat jar | built-in `assembly` + `assemblyRules` | med (port merge strategy) |
| sbt-buildinfo 0.13.1 | BuildInfo | `mill.contrib.buildinfo.BuildInfo` | low |
| sbt-dynver (via ci-release) | git version | `VcsVersion.vcsState().format()` | low |
| sbt-git (readable console) | cosmetic | **drop** | none |
| proguard-base 7.9.1 (lib dep, not plugin) | shrink jar | custom Mill `Task` calling ProGuard API directly (port as-is) | med |
| sbt-stryker4s (gated snapshot) | mutation | **no stable Mill plugin** — keep as external `scripts/run-stryker.sh` or drop | med |
| sbt-stainless | (already NOT used — standalone tool) | keep script path | none |

---

## 3. Custom tasks / keys to port

From `build.sbt`:
- `mcpLauncher` (taskKey) — writes dev launch script from `Runtime/fullClasspath` (resolve virtual paths via fileConverter — Mill uses real paths, simpler).
- `mcpClientConfig` (inputKey, arg = client name / "all") — installs `scripts/scalasemantic-mcp.sh` to `~/.local/bin`, prefetches jar, merges MCP client config. **Uses meta-build class `ScalaSemanticConfigMerger`.** → Mill `T.command(client: String)`.
- `corpusFetch` (taskKey, on root) — **uses meta-build class `CorpusFetch`**; fetches checksum-verified corpora into `target/vendor-corpus`.
- `proguard` (taskKey, mcp) — shrink assembly jar via ProGuard config (long `-keep` list, uses `java.home/jmods/java.base.jmod`).
- `testShrunk` (taskKey, mcp) — builds shrunk jar, filters compile-cp out of test-cp, runs 3 named JUnit suites forked against shrunk jar.
- `stainlessVerify` (taskKey, analysis) — shells `scripts/stainless-verify.sh`, fails on non-zero.
- `compatGolden` (taskKey, compat-fixtures) — compile, copy emitted `*.semanticdb` → `analysis/src/test/resources/compat/scala-<binVer>`.
- `latestReleaseVersion` (plain val) — `git tag --list v*` max, feeds docs `@VERSION@`.

Command aliases (`addCommandAlias` — **no Mill equivalent**, make scripts or `T.command`):
- `prePush` = `clean; scalafmtCheckAll; scalafixAll --check; Test/testOnly *; compatGoldenAll; stainlessVerify`
- `compatGoldenAll` = loop `++<ver> compatFixtures/compatGolden` over `compatScalaVersions`

---

## 4. commonSettings (applied to core, pc, analysis, mcp) — replicate per module

- `semanticdbEnabled := true` + `semanticdbVersion := scalafixSemanticdb.revision` → Mill built-in `def semanticDbEnabled = true` (or `-Xsemanticdb` scalacOption).
- Cross-version scalacOptions switch:
  - Scala 2.12: `-Xfatal-warnings -Ywarn-unused:imports,locals,patvars,privates`
  - else (3.x): `-Werror -Wunused:all`
  - always: `-Wconf:msg=.*unused.*:e`
- wartremover: `wartremoverWarnings ++= [Var, MutableDataStructures, NonUnitStatements, Throw, Return, AsInstanceOf, IsInstanceOf, Null]`; `Compile/wartremoverErrors ++= strictCompileWarts` (18 warts); **`Test/wartremoverErrors --= strictCompileWarts`** and Test scalacOptions strip the strict traversers. → Mill: separate scalacOptions for main vs `object test`.

`strictCompileWarts` (18): ArrayEquals, ArrayToString, EitherProjectionPartial, Enumeration, IterableOps, JavaNetURLConstructors, LeakingSealed, ListAppend, MapUnit, ObjectThrowable, OptionPartial, PartialFunctionApply, SeqApply, StringPlusAny, TripleQuestionMark, TryPartial, While. (list duplicated as `strictCompileWartNames` for Test opt filtering.)

---

## 5. Publishing metadata (`ThisBuild / …`) → Mill `PublishModule`

org `io.github.mercurievv`, organizationName, homepage, licenses (MIT), developers (mercurievv),
scmInfo, `versionScheme := "early-semver"`. Central host via env `SONATYPE_CREDENTIAL_HOST=central.sonatype.com`.
→ `def pomSettings`, `def publishVersion = VcsVersion.…`, `sonatypeCentric` config in mill-ci-release.

---

## 6. sbt-only hacks to DROP (no Mill port needed)

- `root/initialize` block copying slf4j-nop into sbt boot dir (silences StaticLoggerBinder). Mill has no sbt boot-log noise.
- slf4j-nop in `project/build.sbt` and `project/plugins.sbt` (silences coursier SLF4J warning in meta-build).
- `useReadableConsoleGit`, `Global/excludeLintKeys`, `conflictWarning := disable` (meta-build cross-suffix workaround).

---

## 7. Meta-build Scala sources to RELOCATE — DONE

Package `com.github.mercurievv.scalasemantic.sbtplugin`, compiled as sbt meta-build, imported by `build.sbt`:
- `project/ScalaSemanticConfigMerger.scala` (16.3K) — used by `mcpClientConfig` (JSON/TOML/YAML MCP config merge + rules/steer writing).
- `project/CorpusFetch.scala` (4.1K) — used by `corpusFetch`.

Ported into `build.mill` as `ConfigMerger` and `CorpusFetch` objects (neither used sbt-specific APIs
beyond `File`/`IO`/`Logger`, swapped for `os.Path`/os-lib). `mcpClientConfig(client: String)` and
`corpusFetch()` are now Mill root-level `Task.Command`s — `mill mcpClientConfig --client <name>`,
`mill corpusFetch`. `corpusFetch` keeps the repo-root `target/vendor-corpus` path (THIRD_PARTY.md
contract), not Mill's `out/`. `project/*.scala` left in place until `project/` is deleted at cutover.

---

## 8. CI workflow sbt invocations (`.github/workflows/`) — MOSTLY DONE

### ci.yml
| job | step | now runs |
|---|---|---|
| build | Check formatting | `./mill mill.scalalib.scalafmt/checkFormatAll __.sources` |
| build | Regenerate golden | `./mill compatGoldenAll` |
| build | Test | `./mill core.test.testForked pc.test.testForked analysis.test.testForked mcp.test.testForked` |
| verify | Verify contracts | `./mill analysis.stainlessVerify` (env `STAINLESS_TIMEOUT=30`) |
| docs-site | Render docs | `./mill docs.run` |
| publish | Publish | **still `sbt --batch ci-release`** — no working Mill 1.x publish path (§2) |
| release | Build fat jar | `./mill mcp.assembly`; asset renamed from Mill's fixed `out.jar` to `scalasemantic-mcp.jar` on copy |

Every job dropped `sbt/setup-sbt@v1` + `cache: sbt` (except `publish`, which kept both). No
replacement cache step was added yet — `setup-java`'s `cache:` input only knows maven/gradle/sbt,
not Mill; a real fix would be an explicit `actions/cache@v4` over `~/.cache/coursier` + `~/.mill`.
Jobs build off the `./mill` bootstrap script committed at repo root (self-downloads the pinned
Mill 1.1.7 native binary — no separate install action needed).

### mutation.yml — INTENTIONALLY LEFT ON SBT
Whole stryker4s toolchain (`sbtPlugin3/publishLocal`, the `stryker` sbt task,
`scripts/run-stryker.sh`) is sbt-plugin-bound with no Mill plugin at all — worse than scalafix/
ci-release, which at least publish a stale (wrong-Mill-major) artifact. Left unchanged; the file
header now says so explicitly so this doesn't read as an oversight.

### scala-steward.yml
- `scala-steward-action@v2` — **understands sbt & Mill both**; left untouched, not yet validated
  that it reads Mill deps correctly now that both `build.sbt` and `build.mill` exist side by side.

---

## 9. Git hooks (`.githooks/`, `core.hooksPath=.githooks`) — DONE

| hook | now runs |
|---|---|
| pre-commit | `./mill mill.scalalib.scalafmt/reformatAll __.sources` then `git add -u` — dropped the `exec` the sbt version used, since `exec` replaced the shell and made the `git add -u` re-stage step **dead code** (a pre-existing bug, now incidentally fixed) |
| pre-push | `exec ./mill prePush` |

`prePush` in `build.mill` now chains `checkFormatAll` → `compatGoldenAll` → all 4 modules' tests →
`stainlessVerify`, matching the sbt alias minus the `scalafix --check` step (dropped per §2).
Verified end-to-end locally: `./mill prePush` — 643/643 SUCCESS.

---

## 10. Scripts referencing sbt (`scripts/`, `tree2m`)

| file | sbt usage |
|---|---|
| `scripts/run-stryker.sh:171` | `sbt --batch -Dstryker=true "${args[*]}"` (mutation) |
| `scripts/token-live-metrics.sh:25` | `sbt --batch "mcp/testOnly …TokenLiveMetricsSuite"` |
| `scripts/agent-run.sh:131,137,138` | doc text: `sbt --error compile / test / prePush` |
| `scripts/mutation-summary.sh:39` | help text mentions `sbt -Dstryker=true` |
| `scripts/scalasemantic-mcp.sh:205-239` | build-tool detector — already handles sbt/**Mill**/Gradle/scala-cli; Mill branch prints manual snippet (may want to auto-enable now) |
| `scripts/install.sh:5`, `scripts/bump-version.sh:4`, `scripts/config.sh:35`, `scripts/gen-release-notes.sh:8` | comments referencing sbt/dynver/ci-release |

`tree2m` — relies on the pre-push hook running sbt checks; once hook is Mill, `tree2m` unchanged but gate becomes Mill.

---

## 11. Config files (non-.sbt) — mostly reusable

- `.scalafmt.conf`, `.scalafix.conf` — reused as-is by Mill scalafmt/scalafix.
- `stryker4s.conf` — Stryker config, tied to sbt plugin runner.
- `project/build.properties` (`sbt.version=2.0.1`) → delete; add `.mill-version`.

---

## 12. Docs to update after migration

`CLAUDE.md` (Build/test + Releasing sections), `docs/RELEASING.md`, `docs/getting-started/integration.md`,
`docs/project/development.md`, `README.md`, `.claude/skills/prepush-setup/`, and any agent prompts naming `sbt`.

---

## Priority / risk summary

**Hardest (do first, may block):**
1. ~~**wartremover**~~ — done: compiler plugin + scalacOptions wired manually, per-module, main-vs-test split.
2. **ci-release → Central Portal** publishing + PGP signing — **NOT ported**, confirmed no mill1.x `mill-ci-release` build exists yet; `publish` job stays on sbt.
3. **stryker4s mutation** — no *released* Mill plugin (support merged upstream, unreleased — see §2/TODO); proven working via a local snapshot but deliberately not wired into the main build (would break CI/other devs). `mutation.yml` stays on sbt for now (see §8).
4. ~~Relocate meta-build helpers (`ScalaSemanticConfigMerger`, `CorpusFetch`) into `build.mill`~~ — done.
5. ~~**scalafix**~~ — done: `mill-scalafix` itself stays confirmed-broken (only published build targets Mill 0.13, fails TASTy unpickling under Mill 1.1.7), but scalafix runs anyway via `ch.epfl.scala:scalafix-interfaces` called directly from `build.mill` — wired into `prePush` and CI.

**Medium:** ~~assembly merge strategy, ProGuard task, testShrunk, BuildInfo, compat cross-golden~~ — done.
mdoc-library docs task — wired (`./mill docs.run`), not fully exercised (mdoc render not run end-to-end
in this session). CI/hook rewrites — done except `publish`/`mutation.yml` (see above).

**Found and fixed along the way (draft `build.mill` bugs, not sbt-parity gaps):**
- `CompatModule` (`compat-fixtures`) had no `sources` override, so Mill's default `SbtModule` source
  set (`src/main/scala`) silently missed the cross-version fixtures under `src/main/scala-2.13` /
  `src/main/scala-3` — `allSourceFiles` came back empty and **nothing compiled at all**. Fixed by
  overriding `sources` per `crossValue`.
- Even once sources were found, plain `compile()` didn't emit `*.semanticdb` for either cross
  version: Scala 2.13 needs the `semanticdb-scalac` compiler plugin (Scala 3's native
  `-Xsemanticdb` doesn't apply), which wasn't wired at all. Fixed by adding
  `scalacPluginMvnDeps`/`scalacOptions` conditional on `crossValue`, and reading golden output from
  `compile().classes.path` instead of the (empty) `semanticDbData()` target.
- Together these meant `compatGoldenAll` had never actually produced a semanticdb file in this
  draft — `CompatSuite` and 6 other tests that depend on the compat golden fixtures (`AnalyzerSuite`,
  `McpSuite`) were silently failing. All pass now (`./mill prePush` — 643/643 SUCCESS).

**Trivial / drop:** slf4j-nop hacks, `initialize` boot-copy, sbt-git cosmetics, `conflictWarning`.
