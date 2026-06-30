# Stryker4s Mutation Testing Research

This research document details the setup, mechanics, trade-offs, and configurations for introducing Stryker4s mutation testing into the ScalaSemantic project—an sbt 2.0.0 / Scala 3.8.4 multi-module repository.

---

## 1. Plugin Installation and Scala 3 / sbt 2.0 Compatibility

To integrate Stryker4s, the sbt plugin must be added to the project.

### Version Mapping
- **Scala Version**: `3.8.4` (currently used by the project).
- **sbt Version**: `2.0.0` (as defined in [build.properties](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/project/build.properties)).
- **Stryker4s Version**: Stryker4s has introduced support for sbt 2.x starting with `0.16.x` and release candidates for sbt 2.0.0 (specifically starting around version `0.16.5` / `0.17.0-RC1` or newer). We should configure:
  ```scala
  // project/plugins.sbt
  addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.17.0")
  ```

### Known Compatibility Gaps in Scala 3
- **Scala 3 Language Dialect Support**: Stryker4s compiles mutated ASTs using the standard Scala 3 compiler. Advanced language constructs (such as givens, using clauses, opaque type aliases, and enums) are parsed correctly in modern releases.
- **Dialect Settings**: If using specific dialect flags (such as `-source:future`), the configuration file `stryker4s.conf` supports:
  ```hocon
  scala-dialect = "scala3"
  ```
- **Incremental Compilation**: sbt 2.0.0 uses Zinc's incremental compiler, which is fully compatible with Stryker4s's mutation switching mechanism.

---

## 2. Execution Placement: target/ vs Worktree Clone

Stryker4s runs mutations in a "sandbox" by copying source files to a temporary directory (by default under a random temp path, or at `target/stryker4s-tmpDir` if `strykerStaticTmpDir := true` is enabled). It compiles the mutated sources once using mutation switching.

### SemanticDB Generation & Corruption Risks
In the ScalaSemantic project, sbt generates SemanticDB files under `<module>/target/out/.../meta/META-INF/semanticdb/**` because `semanticdbEnabled := true` is configured in [build.sbt](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/build.sbt). Dogfood tests run from the repo root and load the repository's own compiled SemanticDB to verify MCP tool outputs.

Because Stryker4s compiles mutated code using sbt, the following occurs:
1. **Instrumented Compilation**: Stryker4s compiles the sources with all possible mutants instrumented in the code (using conditional checks switching on the `ACTIVE_MUTATION` env var).
2. **Target Directory Overwriting**: sbt outputs the compiled class files and their corresponding SemanticDB metadata into the main project's `target/` directories.
3. **SemanticDB Pollution**: The emitted SemanticDB will represent the instrumented ASTs rather than the clean, original ASTs. If dogfood tests run after Stryker4s compilation without a clean rebuild, they will read corrupted SemanticDB metadata, leading to test failures (due to offset shifts or extra synthetic nodes).

### Trade-offs & Recommendations

| Execution Strategy | Pros | Cons |
| :--- | :--- | :--- |
| **In-place target/ run** | • Faster compilation (reuses existing Zinc cache).<br>• Simpler setup. | • Pollutes the developer's main `target/` folder with instrumented classes and polluted SemanticDB files.<br>• Requires running `sbt compile` (or `sbt clean; sbt compile`) manually after a Stryker run to restore clean SemanticDB files. |
| **Isolated Git Worktree run** | • Complete isolation of target folders.<br>• Zero risk of corrupting the developer's local `target/` folder or dogfooded SemanticDB.<br>• Perfect for CI runs. | • Higher overhead: needs to clone/create worktree and run compilation from scratch (no shared Zinc cache). |

### Concrete Recommendation
We recommend running Stryker4s inside an **isolated git worktree** (e.g. using `git worktree add`). For local developer convenience, we should configure a custom command or script that runs `sbt stryker` followed by a recompilation step (`sbt compile`) to automatically clean up the target folders.
Additionally, we should avoid setting `strykerStaticTmpDir := true` to keep Stryker4s's temporary files outside the main target directory where they might otherwise pollute incremental build caches.

---

## 3. Mutation Score & Threshold Semantics

Stryker4s exposes configuration parameters in [stryker4s.conf](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/stryker4s.conf) to determine pass/fail criteria:

### Thresholds Configuration
- **`thresholds.high`** (default: `80`): The score at or above which the run is considered fully successful and green.
- **`thresholds.low`** (default: `60`): The warning boundary. Scores below `high` but above `low` are marked yellow (warnings).
- **`thresholds.break`** (default: `0`): The hard failure boundary. If the final mutation score falls below this number, the Stryker4s process exits with exit code `1` (failing the CI build).

```hocon
thresholds {
  high = 75
  low = 50
  break = 40
}
```

### Reporters
Stryker4s supports several output formats:
1. **`console`**: Prints a summary and detailed diffs of surviving mutants directly to stdout/stderr.
2. **`html`**: Generates a rich, interactive static web page (under `target/stryker4s-report/`) where developers can browse files and inspect mutants line-by-line.
3. **`json`**: Generates a machine-readable JSON summary for integrations.
4. **`dashboard`**: Publishes mutation scores to the Stryker Dashboard (requires an API key).

We recommend using `["console", "html"]` for local runs, and adding `"json"` for CI processing.

---

## 4. Runtime Cost Expectations & Scoping

Mutation testing is computationally expensive because the test suite must be run repeatedly for every mutant.

### Estimated Costs
- For the 3 modules: [core](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/core/), [analysis](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/analysis/), and [mcp](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/mcp/):
  - **core**: Small, stable codebase (SemanticDB loading). Low mutation density.
  - **analysis**: The analyzer engine, which contains the bulk of the logic. High mutation density. Runs dogfooding tests.
  - **mcp**: Hand-rolled stdio JSON-RPC loop. Medium mutation density.
  - Running mutation tests on the entire repository can take anywhere from **5 to 15 minutes** depending on the test suite execution time.

### Scoping and Filtering Mutations
To keep local iterations and CI runs fast and affordable, we must exclude boilerplate, autogenerated code, and non-critical modules.
This is configured using HOCON glob patterns in the `mutate` array inside [stryker4s.conf](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/stryker4s.conf):

```hocon
# stryker4s.conf example
mutate = [
  "analysis/src/main/scala/**/*.scala", // focus mutation testing on the core analysis logic
  "!analysis/src/main/scala/**/generated/**/*.scala" // exclude generated files
]
```
