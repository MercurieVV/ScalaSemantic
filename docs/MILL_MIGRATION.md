# sbt → Mill Migration Catalog

Full inventory of what the current sbt build does, so nothing is dropped when switching to Mill.
Current: **sbt 2.0.1**, Scala **3.8.4**, 5 modules + root aggregate. Sources: `build.sbt`,
`project/plugins.sbt`, `project/build.sbt`, `project/*.scala`, `.github/workflows/*`, `.githooks/*`,
`scripts/*`.

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
| sbt-scalafmt 2.6.1 | format | built-in `ScalafmtModule` | low |
| sbt-scalafix 0.14.7 | lint/rewrite | third-party `com.goyeau::mill-scalafix` | med (verify Scala 3 + rules) |
| sbt-wartremover 3.6.0 | wart rules | **NO Mill plugin** — add wartremover as compiler plugin dep + `-P:wartremover:…` scalacOptions by hand | **HIGH** |
| sbt-ci-release 1.11.2 | dynver + pgp + Sonatype Central | `de.tobiasroeser.mill-ci-release` (`CiReleaseModule` + `VcsVersion`) — confirm **Central Portal** host support | **HIGH** |
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

## 7. Meta-build Scala sources to RELOCATE

Package `com.github.mercurievv.scalasemantic.sbtplugin`, compiled as sbt meta-build, imported by `build.sbt`:
- `project/ScalaSemanticConfigMerger.scala` (16.3K) — used by `mcpClientConfig` (JSON/TOML/YAML MCP config merge + rules/steer writing).
- `project/CorpusFetch.scala` (4.1K) — used by `corpusFetch`.

Mill: move into `build.mill` helper objects, or a `mill-build/src/…` build-classpath module. Must relocate — no `project/` in Mill.

---

## 8. CI workflow sbt invocations (`.github/workflows/`)

### ci.yml
| job | step | command |
|---|---|---|
| build | Check formatting | `sbt --batch scalafmtCheckAll` |
| build | Regenerate golden | `sbt --batch compatGoldenAll` |
| build | Test | `sbt --batch test` |
| verify | Verify contracts | `sbt --batch stainlessVerify` (env `STAINLESS_TIMEOUT=30`) |
| docs-site | Render docs | `sbt --batch docs/run` |
| publish | Publish | `sbt --batch ci-release` (env SONATYPE_*, PGP_*, SONATYPE_CREDENTIAL_HOST) |
| release | Build fat jar | `sbt --batch "mcp/assembly"` |

Also uses `sbt/setup-sbt@v1` + `cache: sbt` in every job → replace with Mill setup (`./mill` bootstrap or `jodersky/setup-mill` style) and Mill cache dirs (`~/.mill`, `out/`).

### mutation.yml
| step | command |
|---|---|
| Build stryker4s | `sbt sbtPlugin3/publishLocal` (in cloned stryker4s repo) |
| Compile clean | `sbt --batch compile` |
| Run Stryker | `scripts/run-stryker.sh --local --module <m>` → internally `sbt --batch -Dstryker=true "<module>/stryker"` |
| Verify tests | `sbt --batch test` |

Env: `STRYKER=1`, `-Dstryker=true` gate, local ivy snapshot. **Whole mutation path is sbt-plugin-bound; no Mill equivalent — hardest to migrate, consider keeping sbt just for this or dropping.**

### scala-steward.yml
- `scala-steward-action@v2` — **understands sbt & Mill both**; should keep working, but validate it reads Mill deps.

---

## 9. Git hooks (`.githooks/`, `core.hooksPath=.githooks`)

| hook | command |
|---|---|
| pre-commit | `sbt -batch -error scalafmtAll` then `git add -u` |
| pre-push | `sbt -batch -error prePush` |

→ Rewrite to `./mill mill.scalalib.scalafmt/reformatAll` (pre-commit) and a Mill prePush target/script (pre-push).

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
1. **wartremover** — no Mill plugin; wire compiler plugin + scalacOptions manually, per-module, main-vs-test split.
2. **ci-release → Central Portal** publishing + PGP signing (mill-ci-release Central support).
3. **stryker4s mutation** — sbt-plugin-only via local snapshot; likely keep sbt-for-mutation or drop.
4. Relocate meta-build helpers (`ScalaSemanticConfigMerger`, `CorpusFetch`) into `build.mill`.

**Medium:** assembly merge strategy, ProGuard task, testShrunk, BuildInfo, compat cross-golden, mdoc-library docs task, all CI/hook/script rewrites.

**Trivial / drop:** slf4j-nop hacks, `initialize` boot-copy, sbt-git cosmetics, `conflictWarning`.
