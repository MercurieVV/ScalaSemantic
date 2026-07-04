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
- [ ] **scalafix (`prePush` / CI lint step)** — `mill-scalafix`'s only published build targets Mill
      0.13 and fails TASTy unpickling under Mill 1.1.7 (confirmed, not guessed — see §2). No
      `scalafixAll --check` equivalent runs anywhere right now. Revisit when a mill1.x build ships.
- [ ] **stryker4s mutation (`mutation.yml`)** — **close, not yet released**: `feat(mill): add Mill
      plugin` (stryker-mutator/stryker4s#2042) merged to `master` 2026-06-17 and is queued in the
      still-open release-please PR for **v0.22.0** (stryker-mutator/stryker4s#2041). Nothing
      published to Maven Central yet — latest release is still v0.21.0 (2026-06-05), no
      `stryker4s-mill*` artifact exists. Revisit once v0.22.0 tags/publishes; then wire the Mill
      plugin into `build.mill` and drop `run-stryker.sh`'s sbt path.

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

**Done:** modules/deps (§1), wartremover/assembly/BuildInfo/ProGuard/testShrunk/compat-cross-golden
(§2–4), meta-build helper relocation (§7), git hooks (§9), `ci.yml` build/verify/docs-site/release
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
| sbt-scalafix 0.14.7 | lint/rewrite | third-party `com.goyeau::mill-scalafix` | **CONFIRMED BROKEN on Mill 1.1.7**: the only published artifact is `mill-scalafix_mill0.13_3:0.5.1` (no mill1.x build exists on Maven Central); loading it throws `scala.MatchError: val <none>` unpickling `ScalafixModule`'s TASTy, and once that trait fails to load ALL of `build.mill` fails to compile (a scalac plugin classpath resolution failure, not a targeted one). Left OUT of `build.mill`; `prePush`/CI skip the scalafix --check step entirely until a mill1.x build ships. |
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
3. **stryker4s mutation** — no Mill plugin at all; `mutation.yml` stays on sbt intentionally (see §8).
4. ~~Relocate meta-build helpers (`ScalaSemanticConfigMerger`, `CorpusFetch`) into `build.mill`~~ — done.
5. **scalafix** — **confirmed broken**, not just risky: `mill-scalafix`'s only published build targets Mill 0.13 and fails TASTy unpickling under Mill 1.1.7. Dropped from `prePush`/CI until a mill1.x build ships.

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
