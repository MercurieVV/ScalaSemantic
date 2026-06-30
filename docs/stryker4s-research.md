# Stryker4s Mutation Testing Research

This research document details the setup, mechanics, trade-offs, and configurations for introducing Stryker4s mutation testing into the ScalaSemantic project—an sbt 2.0.0 / Scala 3.8.4 multi-module repository.

---

## 1. Plugin Installation and Scala 3 / sbt 2.0 Compatibility

To integrate Stryker4s, the sbt plugin must be added to the project.

### Version Mapping
- **Scala Version**: `3.8.4` (currently used by the project).
- **sbt Version**: `2.0.0` (as defined in [build.properties](../project/build.properties)).
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
In the ScalaSemantic project, sbt generates SemanticDB files under `<module>/target/out/.../meta/META-INF/semanticdb/**` because `semanticdbEnabled := true` is configured in [build.sbt](../build.sbt). Dogfood tests run from the repo root and load the repository's own compiled SemanticDB to verify MCP tool outputs.

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

Stryker4s exposes configuration parameters in [stryker4s.conf](../stryker4s.conf) to determine pass/fail criteria:

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
- For the 3 modules: [core](../core/), [analysis](../analysis/), and [mcp](../mcp/):
  - **core**: Small, stable codebase (SemanticDB loading). Low mutation density.
  - **analysis**: The analyzer engine, which contains the bulk of the logic. High mutation density. Runs dogfooding tests.
  - **mcp**: Hand-rolled stdio JSON-RPC loop. Medium mutation density.
  - Running mutation tests on the entire repository can take anywhere from **5 to 15 minutes** depending on the test suite execution time.

### Scoping and Filtering Mutations
To keep local iterations and CI runs fast and affordable, we must exclude boilerplate, autogenerated code, and non-critical modules.
This is configured using HOCON glob patterns in the `mutate` array inside [stryker4s.conf](../stryker4s.conf):

```hocon
# stryker4s.conf example
mutate = [
  "analysis/src/main/scala/**/*.scala", // focus mutation testing on the core analysis logic
  "!analysis/src/main/scala/**/generated/**/*.scala" // exclude generated files
]
```

---

## 5. POC Run: Analysis Module

Issue #130 ran the first local proof-of-concept against the `analysis` module.

### Configuration used

- Plugin loading is gated behind `-Dstryker=true` / `STRYKER=1` because the published
  `sbt-stryker4s` release currently has an sbt 2.0 final ABI issue in this build. The POC used the
  locally published snapshot configured in `project/plugins.sbt`.
- Mutation scope: `analysis/src/main/scala/**/*.scala`.
- Test scope: fixed-input analysis/model suites from `stryker4s.conf`.
- Reporters: `html` and `json`.
- Thresholds: `high = 80`, `low = 60`, `break = 0`.

The first run with `low = 60` and `break = 60` failed during config loading:

```text
'low' (60) must be greater than 'break' (60)
```

For the POC, `break` was set to `0` so mutation testing records a baseline without making the sbt
task fail on score. The follow-up alerting task should choose the real hard-fail threshold.

### Command and reports

```sh
STRYKER=1 sbt -Dstryker=true "analysis/stryker"
```

Reports were emitted under:

- `analysis/target/stryker4s-report/1782862063904/report.json`
- `analysis/target/stryker4s-report/1782862063904/index.html`

The JSON report was copied to the stable tracked path `mutation-report.json`.

### Results

| Metric | Value |
|---|---:|
| Wall-clock reported by sbt | 32 s |
| Files mutated | 9 |
| Mutants generated | 871 |
| Mutation score | 69.33% |
| Detected / scored mutants | 563 / 812 |
| Killed | 563 |
| Survived | 103 |
| No coverage | 146 |
| Compile error | 1 |
| Ignored/static | 58 |

Stryker generated one compile-error mutant and discarded it before the successful instrumented test
run. It also skipped 146 no-coverage mutants and 58 static mutants.

The summary alert script reported the score below the configured `high` threshold and surviving
behavior-critical mutator types:

- `ConditionalExpression`: 16 survivors
- `EqualityOperator`: 34 survivors
- `LogicalOperator`: 36 survivors

### SemanticDB safety check

After the mutation run, `sbt --batch test` passed. The test command recompiled clean analysis and
mcp classes and then completed the MCP dogfood tests successfully:

```text
Passed: Total 31, Failed 0, Errors 0, Passed 31
```

This confirms the POC mutation run did not leave the worktree in a broken state for the normal
dogfood test path once the standard sbt test command regenerated clean outputs.
