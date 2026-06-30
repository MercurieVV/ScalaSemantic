# Mill Capability Comparison

This document maps the build capabilities identified in the [sbt build inventory](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/docs/research/sbt-build-inventory.md) to their Mill equivalents, evaluating feasibility, identifying gaps, and outlining the migration path.

---

## 1. Version and Plugin Inventory Mapping

| sbt Capability | Mill Equivalent | Maturity | Effort & Risk | Mill Doc Cite / Details |
| :--- | :--- | :---: | :---: | :--- |
| **sbt 2.0.1** | Mill build tool (e.g. `0.11.x` or `0.12.x` line). | High | **Low** | [Mill Official Docs](https://mill-build.org/mill/Intro_to_Mill.html). Mill uses Scala for builds. |
| **Scala 3.8.4** | `def scalaVersion = "3.8.4"` in Mill `ScalaModule`. | High | **Low** | [Mill Scala Build](https://mill-build.org/mill/Scala_Build_Software.html). |
| **conflictWarning disable** | Native dependency management in Mill (ivy / coursier). | High | **Low** | Mill automatically handles dependency resolution. Conflict suppression is rarely needed. |
| **sbt-scalafix (0.14.7)** | `import mill.scalalib.scalafix.ScalafixModule`. | Medium | **Medium** | [mill-scalafix plugin](https://github.com/hhalex/mill-scalafix). Configured by extending the trait. |
| **sbt-scalafmt (2.6.1)** | `import mill.scalalib.scalafmt.ScalafmtModule`. | High | **Low** | [Mill Scalafmt Support](https://mill-build.org/mill/Scalafmt_Support.html). |
| **sbt-wartremover (3.6.0)** | No official Mill plugin exists. Must pass compiler options manually. | Low | **High** | Requires adding WartRemover compiler plugin and options manually to `def scalacPluginIvyDeps` and `def scalacOptions`. |
| **sbt-ci-release (1.11.2)** | `import $ivy.`lolgab::mill-ci-release::0.1.x`` | Medium | **Medium** | [mill-ci-release](https://github.com/lolgab/mill-ci-release). Portably replaces dynver, signing, and Sonatype publish. |
| **sbt-assembly (2.3.1)** | `def assembly = T { ... }` (built-in task). | High | **Low** | [Mill Assembly Task](https://mill-build.org/mill/Scala_Build_Software.html#_assembly_fat_jars). Supports custom merge strategies. |
| **sbt-buildinfo (0.13.1)** | `import $ivy.`com.lihaoyi::mill-contrib-buildinfo::...`` | High | **Low** | Built-in contrib plugin for generating source files with version metadata. |
| **ProGuard (7.9.1)** | Custom task invoking ProGuard jar via Java process. | High | **Medium** | Written as a plain Scala task in `build.mill`, manually resolving classpath files. |
| **sbt-stryker4s (snapshot)** | `stryker4s` CLI run against build. | Medium | **High** | No mature Mill plugin for Stryker4s. Must invoke `stryker4s` CLI directly in the worktree. |
| **SLF4J NOP binding** | *N/A* (sbt-specific issue). | *N/A* | **None** | Mill does not have sbt's boot logger noise. |
| **sbt-stainless plugin** | CLI execution in custom script. | High | **None** | We already run Stainless via custom script, which stays build-independent. |

---

## 2. Module and Dependency Graph Mapping

| sbt Module | Mill Module Equivalent | Effort & Risk | Details |
| :--- | :--- | :---: | :--- |
| **`core`** | Extend `ScalaModule` | **Low** | Normal module. Defs map directly to Mill. |
| **`pc`** | Extend `ScalaModule` | **Medium** | Needs `def forkArgs` and custom `forkEnv` to preserve presentation compiler test classpath. |
| **`analysis`** | Extend `ScalaModule` | **Low** | Normal module with third-party dependencies (`upickle`, `refined`). |
| **`mcp`** | Extend `ScalaModule` | **Medium** | Must test-depend on `analysis` test classes. In Mill, this is done by adding `analysis.test` to `def moduleDeps` of `mcp.test`. |
| **`sbtPlugin`** | Keep in sbt or build via raw dependencies | **High** | The plugin is inherently sbt-specific. If we migrate the main build, we should keep `sbt-plugin` module under a minimal sbt build or use specialized sbt-plugin-mill tasks. |
| **`compatFixtures`** | Extend `ScalaModule` with crossScalaVersions | **Medium** | Mill supports cross-compilation via `Cross[ScalaModule]`. We can configure a task that copies target SemanticDB files. |
| **`docs`** | Custom Mill module running mdoc library | **Low** | Run `mdoc` main class directly as a Java execution task in Mill. |

---

## 3. Shared Settings, Tasks, and Aliases Mapping

### SemanticDB Output Location
- **Sbt Behavior**: SemanticDB files are written under `target/out/.../meta/META-INF/semanticdb/**`.
- **Mill Behavior**: Mill outputs compiled classes and SemanticDB files under `out/<module>/compile.dest/classes/META-INF/semanticdb/`.
- **Compatibility**: The loader `SemanticIndex.fromProject(".")` walks the project directory recursively to find `*.semanticdb` files, skipping directories starting with `.`. Since Mill's `out/` directory does not start with `.`, the loader **will automatically discover** Mill's SemanticDB files without any code changes.

### Custom Tasks / Aliases
- **`prePush`**: Implemented as a Mill command:
  ```scala
  def prePush() = T.command {
    scalafmtCheckAll()()
    scalafixAll()()
    test.test()()
    // run stainless script
  }
  ```
- **`compatGoldenAll`**: Implemented by cross-compiling the `compatFixtures` module across target Scala versions and running a custom resource-copy task.

---

## 4. Release, Publishing, and CI Touchpoints

- **CI Workflow**: Replacing `sbt` calls with `./mill` in `.github/workflows/ci.yml`.
- **Publishing**: `mill-ci-release` replaces the dynver versioning, GPG signing, and Sonatype upload seamlessly.
- **Assembly Jar**: Mill's `assembly` task builds the fat jar and places it under `out/mcp/assembly.dest/out.jar`, which can be renamed and attached to the GitHub Release.

---

## 5. High-Risk Migration Items

1. **sbtPlugin Compatibility**: Building and testing the sbt plugin is native to sbt but requires custom dependency configurations in Mill. It is recommended to keep `sbt-plugin` in a minimal sub-sbt project.
2. **Stryker4s Integration**: Lacking a mature Mill plugin, mutation testing will need to be executed via Stryker4s CLI runner scripts rather than `sbt "analysis/stryker"`.
3. **WartRemover Configuration**: WartRemover lacks a standard Mill plugin. Configuring it manually requires correct compiler plugin flags and compiler dependency setup.

---

## 6. New Capabilities Unlocked by Mill

1. **Self-Analysis**: Because Mill build files (`build.mill` / `build.sc`) are written in compile-time verified Scala code, compiling them produces SemanticDB files. ScalaSemantic can dogfood itself by running semantic analysis directly on the project's own build code.
2. **Strict Caching and Parallelism**: Mill caches tasks out-of-the-box and parallelizes builds, speeding up clean compiles and test execution times significantly.
