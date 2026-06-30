# Stryker4s Mutation Testing Alert and CI Strategy

This strategy document defines the trigger cadence, metrics reporting, per-module rollout policy, and pass/fail thresholds for introducing Stryker4s mutation testing into the ScalaSemantic CI pipeline. It builds on the findings of the [Stryker4s Mutation Testing Research](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/docs/stryker4s-research.md).

---

## 1. CI Thresholds: Fail vs. Notify

To ensure mutation testing acts as an effective quality gate without unnecessarily blocking development, we define a two-tiered threshold strategy (warning vs. error).

### Threshold Calibration (Dynamic Rule)
Because the POC baseline score from [#130](https://github.com/MercurieVV/ScalaSemantic/issues/130) is not yet measured, we establish a dynamic calibration rule to be set once the baseline is established:

- **`thresholds.break` (Hard Failure)**: Set to `POC_baseline - 5%` (e.g., if the baseline is `65%`, set `break = 60`).
  - *Behavior*: If the final mutation score falls below this value, the CI job exits with code `1`, failing the PR check. This catches significant drops in test coverage or regression quality.
- **`thresholds.low` (Soft Warning)**: Set equal to the `POC_baseline` (e.g., `65%`).
  - *Behavior*: If the mutation score falls below the baseline but stays above the `break` threshold, the build succeeds but flags a warning in the logs and PR comments.
- **`thresholds.high` (Quality Target)**: Set to `80%`.
  - *Behavior*: Any score at or above `80%` is considered fully compliant (green status).

### Rollout Phase (Grace Period)
During the first two weeks or the first 5 pull requests following the initial merge, we recommend setting `break = 0` (notify-only). This allows the team to verify that the mutation scores are stable across various changes before enforcing build breaks.

---

## 2. Metrics Exposure and Reporting

We recommend exposing mutation testing metrics in three locations:

1. **Console Output (CI Logs)**: Standard output summary of killed, survived, and timed-out mutants.
2. **PR Comment Summary**: A GitHub Actions workflow should post a comment on the PR showing:
   - **Mutation Score**: The final percentage score and how it compares to the master branch baseline.
   - **Summary Stats**: Total mutants, killed, survived, and ignored.
   - **Link to Report**: Pointers to the detailed HTML report.
3. **HTML Artifact Upload**: Zip and upload the `target/stryker4s-report/` directory as a GitHub workflow artifact on every run. Developers can download and open this locally to inspect surviving mutants line-by-line.
4. **Stryker Dashboard**: Defer publishing to the public dashboard for now to avoid managing API key secrets in the repository, keeping setup simple.

---

## 3. CI Cadence and Execution Environment

Due to the high runtime cost of running mutation tests (estimated 5-15 mins for the full codebase), running it on every PR commit is unsustainable.

### Trigger Cadence
- **Nightly Run**: Trigger a full mutation test run on the `master` branch every night via a cron schedule. This records the baseline health of the codebase.
- **On-Demand PR Run (Label-Gated)**: Run mutation testing on a pull request only if:
   - The PR has the label `ci:run-mutation` applied.
   - The PR modifies critical business logic inside the [analysis](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/analysis/) module.

### Isolation (Avoiding SemanticDB Pollution)
As identified in the research report, sbt compilation during a Stryker run overwrites target class directories and generates polluted SemanticDB files.
- *CI Requirement*: The mutation workflow MUST run in its own isolated runner step and NOT share caching folders with the standard compile/test checks, or it must run inside a separate git worktree checkout. This prevents the polluted build outputs from corrupting subsequent validation jobs.

---

## 4. Per-Module Rollout Policy

We recommend rolling out mutation testing in phases:

1. **Phase 1: `analysis` module** ([analysis](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/analysis/)): Focus here first. This contains the semantic analysis engine where logic is dense and test coverage is critical.
2. **Phase 2: `core` module** ([core](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/core/)): Roll out once `analysis` is stable. Since `core` handles static SemanticDB parsing and has stable structure, its threshold can be set higher (e.g. `85%`).
3. **Phase 3: `mcp` module** ([mcp](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/mcp/)): The stdio JSON-RPC loop contains infinite loops and blocking network channels that are difficult to mutate without triggering infinite loops or timeouts. We recommend excluding the stdio channel from mutations.

---

## 5. Configuration Sketch

### HOCON Config (`stryker4s.conf`)
The HOCON configuration file [stryker4s.conf](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/stryker4s.conf) should be structured as follows:

```hocon
stryker4s {
  # Mutate only main sources in the analysis module
  mutate = [
    "analysis/src/main/scala/**/*.scala",
    "!analysis/src/main/scala/**/generated/**/*.scala"
  ]
  
  test-filter = [
    "com.github.mercurievv.scalasemantic.analysis.*"
  ]
  
  reporters = ["console", "html", "json"]
  
  thresholds {
    high = 75
    low = 65
    break = 0  # Set to 0 initially for the grace period
  }
}
```

### GitHub Actions Workflow (CI Sketch)
Sketch for `.github/workflows/mutation-testing.yml`:

```yaml
name: Mutation Testing

on:
  schedule:
    - cron: '0 2 * * *' # Run nightly at 2:00 AM
  pull_request:
    types: [labeled, opened, synchronize]

jobs:
  stryker:
    # Run only on nightly cron or if the label is present
    if: github.event_name == 'schedule' || contains(github.event.pull_request.labels.*.name, 'ci:run-mutation')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: sbt
      - uses: sbt/setup-sbt@v1
      - name: Run Stryker4s
        run: sbt "analysis/stryker"
      - name: Upload HTML Report
        uses: actions/upload-artifact@v4
        with:
          name: stryker4s-report
          path: target/stryker4s-report/
```
