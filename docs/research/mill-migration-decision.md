# Mill Migration Decision: Recommend, Defer, or Reject

This decision document synthesizes the [sbt build inventory](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/docs/research/sbt-build-inventory.md) and [Mill capability comparison](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/docs/research/mill-capability-comparison.md) to evaluate whether ScalaSemantic should migrate its build from sbt to Mill.

---

## 1. Migration Effort Estimate

The table below breaks down the migration work by workstream, referencing specific capabilities audited in the inventory:

| Workstream | Audited sbt Items | Mill Equivalent | Size | Risk / Complexity Rationale |
| :--- | :--- | :--- | :---: | :--- |
| **Module Setup** | `core`, `pc`, `analysis`, `mcp`, `compatFixtures`, `docs` modules | Extend `ScalaModule` or `Cross[ScalaModule]` | **Small** | Declarative mapping of dependencies is straightforward in Mill. |
| **SemanticDB Output & Loader** | `semanticdbEnabled := true`, `SemanticIndex.fromProject(".")` | `out/<module>/compile.dest/classes/META-INF/semanticdb/` | **Small** | Mill writes SemanticDB to `out/...`. Since `out` doesn't start with `.`, the loader automatically finds files with no code changes. |
| **Code Quality Gates** | `sbt-scalafmt`, `sbt-scalafix`, `sbt-wartremover` | `ScalafmtModule`, `ScalafixModule`, custom `scalacOptions` | **Medium** | WartRemover lacks a Mill plugin; configuring the compiler plugin and exclusions must be done manually. |
| **Publishing & Release** | `sbt-ci-release` | `mill-ci-release` | **Medium** | Replaces tag-driven releases, GPG signing, and Sonatype upload. Low risk but high impact on CI. |
| **MCP Assembly & ProGuard** | `sbt-assembly`, `proguard` task | `def assembly = T { ... }`, custom Java task | **Medium** | ProGuard has no Mill plugin; requires a custom Scala task in `build.mill` to call the ProGuard jar. |
| **sbtPlugin Module** | `sbtPlugin` | Hybrid sbt project or complex dependencies | **Large** | **High Risk**. The plugin is inherently coupled to sbt APIs and cross-builds for sbt 1 and 2. Building it in Mill is not cleanly supported. |
| **CI, Automation, & Scripts** | `.github/workflows/ci.yml`, `scripts/agent-run.sh` | Replace `sbt` with `./mill` | **Medium** | Requires updating agent runner prompts, helper scripts, and GitHub workflow cache settings. |

---

## 2. Hard Gaps & Compromises (What Mill Cannot Cover)

Mill **cannot** cover 100% of the currently-used sbt build features out of the box. The following are the major gaps and compromises:

1. **sbtPlugin Module compilation**: The `sbtPlugin` module builds actual plugins for sbt 1.x and sbt 2.x. While Mill can build standard Scala library jars, compiling and testing sbt plugins (which hook into sbt internals) natively is extremely difficult.
   - *Compromise*: We would have to retain a mini-sbt build specifically for `sbt-plugin`, creating a hybrid build setup.
2. **Stryker4s Integration**: There is no mature Mill plugin for Stryker4s.
   - *Compromise*: Mutation testing must be run via the Stryker4s CLI runner in a shell script rather than an integrated build task.
3. **WartRemover Integration**: There is no active Mill plugin for WartRemover.
   - *Compromise*: Must manually inject `-Xplugin` compiler flags and configure exclusions via raw compiler options.

---

## 3. Strategic Upside: Dogfooding the Build

- **Build-as-Scala-code**: Mill build files (`build.mill`) are compiled Scala code. This means compiling the build itself emits `*.semanticdb` files.
- **Self-Analysis**: ScalaSemantic could dogfood itself by running its own analysis tools (like `find_usages`, `class_hierarchy`, `call_path`) directly on the `build.mill` code to analyze build graphs and dependencies.
- **Performance**: Mill's fine-grained task caching and parallel execution can speed up clean compiles and local verification runs.

---

## 4. Final Verdict: DEFER

We recommend **deferring** the migration of the build tool to Mill.

### Rationale
1. **Hybrid Build Complexity**: Because `sbtPlugin` is inherently tied to sbt and cannot be cleanly built/tested in Mill, a migration would force us into a hybrid build (Mill for the main project, sbt for the plugin). This increases rather than decreases build system complexity.
2. **Ecosystem & Tooling Gaps**: The lack of mature Mill plugins for Stryker4s and WartRemover requires custom CLI wrapper scripts and raw compiler option parsing, shifting maintenance burden into custom build code.
3. **Current sbt 2 Stability**: The current sbt 2.x setup is clean, utilizes the latest features, and handles cross-compilation and golden-file generation successfully. The overhead of sbt 2 is currently low enough that a migration does not yield an immediate net positive return.

### Trigger Conditions for Re-evaluation
The migration decision should be re-evaluated if:
1. **Deprecation of sbtPlugin**: The project decides to retire the sbt plugin entirely (e.g., focusing exclusively on the LSP/MCP server and other editors).
2. **Tooling Maturity**: The Scala community releases a mature sbt-plugin building plugin for Mill and official Mill support for Stryker4s.
3. **Build Bottlenecks**: The codebase grows to a size where sbt compilation/test times become a major developer bottleneck that Mill's caching could resolve.

---

## 5. Phased Migration Outline (If Triggered)

If the trigger conditions are met, the migration should follow this phased roadmap:

* **Phase 1: Dual-Build Sandbox**
  - Create `build.mill` side-by-side with `build.sbt` representing only `core` and `analysis` modules.
  - Verify that local tests pass on both tools.
* **Phase 2: MCP & Docs Setup**
  - Wire up `mcp` module, assembly packaging, and custom ProGuard task in `build.mill`.
  - Validate that `SemanticIndex.fromProject(".")` successfully loads the `out/` path SemanticDB.
* **Phase 3: CI & Publishing Cutover**
  - Integrate `mill-ci-release` and rewrite `.github/workflows/ci.yml` to run Mill checks.
  - Archive `build.sbt` once the hybrid `sbtPlugin` build is either migrated or isolated.
