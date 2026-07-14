# Token-Cost Findings: End-to-End Live Run (Part 2)

Issue: #150 (parent: #72)

This page reports what the **live-run** measurement (Part 2 of the
[methodology](token-metrics-methodology.md)) actually produced, as opposed to
what it set out to measure. Part 1's fixture-based proxy numbers
(90.4% overall savings across 5 queries) are unaffected and remain the primary,
fully-reproducible result — see
[Token-Cost Methodology: Results](token-metrics-methodology.md#results). This
page is scoped to Part 2 only: real end-to-end agent token usage, captured
from each engine's own accounting.

**Bottom line: the live run has produced two valid, usable comparisons** —
the same single query (`find-usages-animal`) on two engines (codex / o3 and,
after a 2026-07-03 rerun fixed the login failure, claude-cli /
claude-sonnet-5), 3 repetitions per arm each. The methodology's full target
(5 single-call queries + 2-3 multi-step tasks, across claude-cli / codex /
agy) is still not completed. The gaps and why are documented below rather
than filled with invented numbers.

---

## Target project

- Repository: `https://github.com/MercurieVV/ScalaSemantic.git` (this repo,
  per the methodology's fallback rule: "this repository is an acceptable
  fallback if no suitable external project is identified")
- Commit (codex run): `99b17c3981a1fb50ed20587ab92afcfd7fc2d66a`
- Commit (claude-cli rerun, 2026-07-03):
  `cf9d6bbd8e310f2794460fa784188072095072a7`, ScalaSemantic MCP server
  version `0.3.10` (published launcher). Per-measurement commits are recorded
  in `token-metrics-live-runs.json` as a `targetCommit` field on the
  measurement, since the file's top-level `source.targetCommit` can only pin
  one commit.
- ScalaSemantic MCP server commit under test: same repo (dogfooding)

An earlier attempt (below, [Failed attempt](#failed-attempt-2026-07-02)) used
a different commit, `00a10cf4e267ce7c6eb5783856e797c5ed6edb7b`, and did not
produce usable data.

## Engine coverage

Per the methodology's [Tools/engines](token-metrics-methodology.md#toolsengines-and-how-to-capture-tokens)
section, three engines were in scope: claude-cli, codex, agy. Two have now
produced a valid WITH-vs-WITHOUT comparison:

| Engine | Version | Token accounting source | Outcome |
| --- | --- | --- | --- |
| **codex** | `codex-cli 0.142.5` | `turn.completed.usage` (`codex exec --json`) | **Usable** — see [Result](#result-find-usages-animal-codex--o3) below |
| **claude-cli** | 2.1.199 (Claude Code) | `modelUsage` object in `--output-format json` | **Usable after 2026-07-03 rerun** — the original attempt (v2.1.197) was not logged in and produced zero data; rerun with a logged-in CLI succeeded, see [Result](#result-find-usages-animal-claude-cli--claude-sonnet-5) below |
| **agy** (Gemini CLI) | 1.0.15 | none found | Not usable — in the original attempt token usage was not exposed in print-mode stdout or the selected log file; in the 2026-07-03 rerun attempt agy was no longer logged in to Antigravity at all ("You are not logged into Antigravity"), so it regressed from "answers without token counts" to "no run at all". Per project memory, agy is also Gemini-only, so it can never substitute for claude-cli/codex coverage even once its auth and token reporting are solved |

Two engines now have data on the same task, but the methodology's explicit
warning still applies: "cross-engine comparisons of absolute token counts are
not meaningful" — the codex and claude-cli rows below use different models,
different CLI framing, and were run at different commits, so only each
engine's own WITH-vs-WITHOUT delta is meaningful, not codex-vs-claude
absolute numbers.

## Task coverage

Of the fixed task set (5 single-call queries + 3 multi-step tasks defined in
the methodology), only **one** single-call query has valid measurements
(now on two engines):

| Task id | Status |
| --- | --- |
| `find-usages-animal` | **Valid** — codex/o3 and claude-cli/claude-sonnet-5, 3 reps/arm each, both arms completed through the intended tool path |
| `class-hierarchy-animal` | Not attempted |
| `method-signature-render` | Not attempted |
| `trace-implicit-show` | Not attempted |
| `call-path-a-to-c` | Not attempted |
| `M1` (rename safety) | Not attempted |
| `M2` (implicit chain trace) | Not attempted |
| `M3` (call-path reachability) | Not attempted |

## Result: `find-usages-animal`, codex / o3

Task prompt: *"Find all definitions and references of the `Animal` trait in
this repository's fixture sources."*

3 repetitions per arm, fresh session each run, per the
[rerun protocol](token-metrics-methodology.md#rerun-protocol-live-run).

| Run | Arm | Input tokens | Cache tokens | Output tokens | Total tokens |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | with-mcp | 114 621 | 84 608 | 1 083 | 115 704 |
| 2 | with-mcp | 201 603 | 147 584 | 2 251 | 203 854 |
| 3 | with-mcp | 129 656 | 100 352 | 1 504 | 131 160 |
| 1 | without-mcp | 179 500 | 135 424 | 2 641 | 182 141 |
| 2 | without-mcp | 125 781 | 97 920 | 2 093 | 127 874 |
| 3 | without-mcp | 157 257 | 128 384 | 2 469 | 159 726 |

| Arm | Mean total tokens | Median | Variance |
| --- | ---: | ---: | ---: |
| with-mcp | 150 239.3 | 131 160 | 1.477 × 10⁹ |
| without-mcp | 156 580.3 | 159 726 | 4.958 × 10⁸ |

**Delta (mean): 6 341 tokens, 4.0% reduction.**

Full run data is in `docs/research/token-metrics-live-runs.json`
(raw per-run input) and `docs/research/token-metrics-live.json`
(computed aggregate). Narrative version of this same result:
[`token-metrics-live-run.md`](token-metrics-live-run.md).

### Reading this number honestly

4.0% is far below Part 1's proxy figure of 96.4% for the same query id.
Faithfully to the recorded data, three things stand out:

- **Variance dwarfs the mean effect.** The with-mcp arm's per-run totals
  range from ~115.7k to ~203.9k tokens (variance ≈1.48 × 10⁹, i.e. a standard
  deviation of roughly 38 000 tokens on a ~150 000-token mean); the
  without-mcp arm ranges ~127.9k–182.1k (std. dev. ≈22 000). A 6 341-token
  mean delta is smaller than one standard deviation in either arm. With only
  3 repetitions per arm, this single task's result should be read as **not
  distinguishable from noise**, not as a confirmed 4% real-world saving.
- **End-to-end agent overhead swamps the tool-call savings.** Part 1 isolates
  the MCP response payload itself (100 proxy tokens vs. 2 810 for grep). Part
  2 captures the whole codex turn — system prompt, tool schemas, the model's
  own reasoning/verification steps, and (per the methodology's expectation)
  whatever extra reads the agent chooses to do regardless of which arm it's
  in. At o3's scale of context for this repository, that overhead is large
  enough that a ~2 700-token saved payload is a small fraction of a
  ~150 000-token turn.
- **Cache tokens are large and roughly proportional in both arms**
  (84 608–147 584 cached in with-mcp vs. 97 920–135 424 in without-mcp),
  consistent with the methodology's warning that caching must be reported
  separately since it can otherwise make either arm look artificially cheap.
  They are reported as their own column above, not folded into "input", per
  that requirement.

No conclusion beyond the above should be drawn from this single data point —
one task, one engine, 3 reps is not enough to generalize a savings percentage
for real agent workflows, only enough to establish that the harness itself
works end-to-end (real per-run token accounting captured, aggregated, and
reported per the schema) and that this first real measurement did not
replicate Part 1's proxy-derived savings rate.

## Result: `find-usages-animal`, claude-cli / claude-sonnet-5

Rerun on 2026-07-03 after fixing the login failure that gave claude-cli zero
data in the original attempt. Same task prompt as the codex run, 3
repetitions per arm, fresh session per run (`claude -p`), commit
`cf9d6bbd8e310f2794460fa784188072095072a7`, ScalaSemantic server `0.3.10`.

Setup details that differ from codex and matter for reading the numbers:

- **Arm isolation**: with-mcp used `--mcp-config .mcp.json --strict-mcp-config`
  (ScalaSemantic only); without-mcp used an empty MCP config. Tool
  allowlists: with-mcp `Read,Grep,Glob` + ScalaSemantic read tools;
  without-mcp `Read,Grep,Glob` (the engine also used its built-in
  Bash/Explore agent, which the allowlist mechanism does not remove).
- **Arm validity was verified from session transcripts** (the trap that
  invalidated the first codex attempt): each with-mcp run made 6-8
  `mcp__scala-semantic__*` calls and zero Grep/Read calls; each without-mcp
  run made zero ScalaSemantic calls.
- **Accounting source**: `modelUsage` from `--output-format json`, not the
  top-level `usage` object — the top-level object omits subagent model calls
  (without-mcp run 3 spawned an Explore subagent that `usage` misses
  entirely). `inputTokens` below = input + cacheCreation + cacheRead summed
  over all model calls, so it is total-context-consumed and matches codex
  semantics where `input_tokens` includes cached tokens; `cacheTokens` is
  the cache-read subset.

| Run | Arm | Input tokens | Cache tokens | Output tokens | Total tokens |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | with-mcp | 177 329 | 140 374 | 729 | 178 058 |
| 2 | with-mcp | 141 374 | 119 938 | 887 | 142 261 |
| 3 | with-mcp | 141 473 | 121 642 | 463 | 141 936 |
| 1 | without-mcp | 239 103 | 225 652 | 1 474 | 240 577 |
| 2 | without-mcp | 213 650 | 187 263 | 1 437 | 215 087 |
| 3 | without-mcp | 338 814 | 281 814 | 4 520 | 343 334 |

| Arm | Mean total tokens | Median | Variance |
| --- | ---: | ---: | ---: |
| with-mcp | 154 085.0 | 142 261 | 2.874 × 10⁸ |
| without-mcp | 266 332.7 | 240 577 | 3.073 × 10⁹ |

**Delta (mean): 112 247.7 tokens, 42.1% reduction.**

Unlike the codex 4.0% result, this delta is larger than the run-to-run
noise: the with-mcp arm's standard deviation is ~17 000 tokens and the
without-mcp arm's ~55 000, against a ~112 000-token mean delta (about 2
standard deviations of the noisier arm). The without-mcp run 3 outlier
(343 334 total) is the run that spawned an Explore subagent; excluding it,
the mean reduction is still ~32%. Three repetitions is still too few to
quote 42.1% as a precise savings rate, but on this engine the direction and
rough magnitude of the effect are visible above the noise floor, which the
codex result was not.

## Token-accounting limitations encountered

- **codex**: nested non-interactive `codex exec` runs, when first attempted,
  cancelled ScalaSemantic MCP tool calls outright ("user cancelled MCP tool
  call") rather than executing them — see [Failed attempt](#failed-attempt-2026-07-02)
  below. This was worked around for the successful run above, but the
  underlying cause (why nested non-interactive codex sessions cancel MCP
  calls) was not root-caused as part of this task and may recur.
- **claude-cli**: session-level `/cost`/summary reporting (per the
  methodology) requires an authenticated session; the original attempt ran
  unauthenticated and produced no model run at all. **Resolved 2026-07-03**
  by rerunning with a logged-in CLI. One residual accounting subtlety found
  during the rerun: the top-level `usage` object in `--output-format json`
  only counts the main conversation thread — when the agent spawns a
  subagent (as one without-mcp run did), those model calls appear only in
  `modelUsage`. Use `modelUsage` sums, never top-level `usage`, or
  subagent-heavy runs are silently undercounted.
- **agy**: Gemini's `usageMetadata` (promptTokenCount / candidatesTokenCount /
  totalTokenCount) is documented in the methodology as the intended capture
  point, but agy's CLI did not surface it in print-mode stdout, and the log
  file selected for this attempt did not contain it either. The task
  completed (agy produced an answer) but with no attributable token count, so
  this is a tooling gap in agy's observability, not a failure of the task
  itself. In the 2026-07-03 rerun attempt the situation regressed: agy
  (still 1.0.15) was no longer logged in to Antigravity, so no run happened
  at all; fixing it requires an interactive login before any future attempt.
- **codex model availability**: the o3 model used for the valid codex result
  is no longer selectable on the ChatGPT-account codex CLI as of 2026-07-03
  (the account's default is now `gpt-5.5`, and `-m o3` is rejected with
  "The 'o3' model is not supported when using Codex with a ChatGPT
  account"). Future codex runs will therefore be a **new dataset**
  under a different model, not additional repetitions of the o3 rows above —
  per the methodology's rule that model changes break comparability.
- **General**: none of the three engines' gaps were about the concept of the
  measurement — each engine's own documented accounting mechanism (per the
  methodology's [Tools/engines table](token-metrics-methodology.md#toolsengines-and-how-to-capture-tokens))
  either worked as expected (codex, once MCP calls weren't cancelled) or
  didn't surface in practice (claude-cli auth, agy logging) for reasons
  outside ScalaSemantic itself.

## Failed attempt (2026-07-02)

An earlier attempt, at commit `00a10cf4e267ce7c6eb5783856e797c5ed6edb7b`,
produced no valid comparison and its numbers are **not** part of the result
above. Raw attempt data:
`docs/research/token-metrics-live-attempts.json`.

| Engine | Intended arm | Task | Outcome |
| --- | --- | --- | --- |
| codex | without-mcp | `find-usages-animal` | Completed via repository search fallback (106 849 input / 82 688 cached / 1 612 output / 347 reasoning-output tokens) |
| codex | with-mcp | `find-usages-animal` | ScalaSemantic MCP server was visible to the agent, but every `find_symbol` call returned "user cancelled MCP tool call"; the run fell back to grep/file reads instead (109 553 input / 75 008 cached / 1 292 output / 553 reasoning-output tokens) |
| codex | with-mcp-probe | `find-symbol-animal` | Retried with per-tool auto-approval overrides; `find_symbol` calls still returned "user cancelled MCP tool call" (184 583 input / 142 720 cached / 1 188 output / 298 reasoning-output tokens) |
| claude-cli | — | — | Not logged in; no measurable model run |
| agy | — | — | Answer completed; per-task token usage unavailable |

No aggregate was generated from this attempt
(`aggregateGenerated: false` in the raw file), because the with-mcp arm never
actually exercised ScalaSemantic tools — comparing it against the
without-mcp arm would silently compare two "without-mcp-equivalent" runs
against each other and misreport near-zero or misleading savings. This is
explicitly called out rather than included in any results table, per the
requirement to not fabricate missing per-arm measurements.

The successful run reported above, at a later commit and date, resolved the
tool-call cancellation for codex specifically (the exact fix applied was not
captured in the checked-in artifacts) and is the only attempt whose with-mcp
arm is confirmed to have gone through ScalaSemantic MCP tool calls rather
than a fallback path.

## What remains open

Per the methodology's full plan, the following are still outstanding and
should be tracked as follow-up work rather than assumed complete:

- 4 of 5 single-call queries (`class-hierarchy-animal`,
  `method-signature-render`, `trace-implicit-show`, `call-path-a-to-c`) have
  no live measurement on any engine.
- All 3 multi-step tasks (M1–M3) have no live measurement on any engine.
- agy has zero usable data points across any task, and as of 2026-07-03 is
  blocked on an interactive Antigravity login before any rerun is possible.
- The codex result has only 3 reps/arm with variance larger than the
  observed mean effect, and cannot be extended: o3 is no longer available on
  the account, so additional codex data will be a new gpt-5.5 dataset.
- The claude-cli result (42.1% reduction) is the first live measurement
  where the delta clearly exceeds run-to-run noise, but it is still one
  task, one engine, 3 reps/arm — extending claude-cli coverage to the
  remaining 4 single-call queries and M1–M3 is the highest-value follow-up,
  since the engine, auth, MCP wiring, and transcript-based arm validation
  are now all proven to work.
- The root cause of codex's initial MCP-tool-call cancellation in nested
  non-interactive sessions was not diagnosed (a 2026-07-03 probe ran an MCP
  call through cleanly, so it did not recur, but why it happened remains
  unknown); recurrence risk for future live runs is unknown.
