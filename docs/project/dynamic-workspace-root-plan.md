# Dynamic Workspace Root Plan

## Problem

`Mcp.serve(root, classpath, logging)` (`mcp/src/main/scala/.../mcp/Mcp.scala:221`) binds `root` once at
process launch and builds one `Analyzer`/`SemanticIndex` for that root
(`mcp/src/main/scala/.../mcp/Main.scala:19-29`, `McpTools.all(az, root)`). The MCP subprocess is
spawned once per client session and is never re-launched when the LLM later changes working
directory (e.g. `EnterWorktree`, `git worktree` + `cd`, or any subagent given a different cwd).

Confirmed root causes (see prior investigation, no code changes needed to re-verify):

1. `scripts/scalasemantic-mcp.sh`'s `setup_main` resolves `_project=$(cd -- "." && pwd)` at
   **setup time** and bakes that absolute path into every generated client config
   (`.mcp.json` / `.codex/config.toml` / `.gemini/settings.json` / ...). Because these configs are
   committed to git, every worktree checkout carries the *same* absolute path, so every spawned
   subprocess indexes the original repo root regardless of which worktree actually launched it.
2. Even with a correct config, stdio MCP servers are not auto-reconnected/relaunched by the Claude
   Code harness when the session's cwd changes mid-session (`EnterWorktree`/`ExitWorktree` switch
   session cwd but do not touch already-running MCP subprocesses). This is a known, still-partially-open
   class of bug upstream (anthropics/claude-code#42282, #32747, #27881, #30906) — not something this
   project can fix, only work around.
3. MCP's own `roots` capability (client → server workspace-folder notification) exists in the spec
   and Claude Code's client, but there is no confirmed evidence it fires on `EnterWorktree`/
   `ExitWorktree` specifically. Do not depend on it until independently verified (see Task 3a).

## Goal

The server should default to indexing **wherever its process was actually launched** (no baked-in
path), and should expose an explicit, statefully-tracked "current workspace root" that the LLM can
update after a worktree/cwd change, with a hard rule instructing it to do so. This is a workaround
for a harness-level gap, not a full protocol fix — document it as such.

## Task 1 — Make project root optional / cwd-derived in MCP configs

**Owner target:** shell scripting, no Scala changes.

- File: `scripts/scalasemantic-mcp.sh`, function `setup_main` (`_project=...`) and
  `write_client_configs` (previously emitted setup-time root/classpath arguments).
- Change: stop baking the absolute `_project` path into the generated `args`. Emit `"."` (or omit
  the root positional entirely and let `serve_main`'s default apply) so every generated config is
  cwd-relative and identical/safe across worktree checkouts.
- Verify `Main.scala:26` (`positional.headOption.getOrElse(".")`) already does the right thing with
  no root arg — it does; no Scala change needed for this task alone.
- Regenerate this repo's own `.mcp.json` (and any other client configs under
  `.codex/`, `.gemini/`, `.agents/`, `.cline/`, `.roo/`, `.continue/` if present) via
  `scripts/scalasemantic-mcp.sh setup --client all` (or hand-edit) to confirm the new output has no
  absolute path.
- Classpath-file defaults are covered by `docs/project/classpath-refresh-plan.md`; generated
  configs now omit the classpath argument and rely on root-relative metadata discovery.

**Acceptance check:** `git grep -n "$(pwd)"` (or the known absolute repo path) inside every
generated client config returns nothing after re-running `setup`.

## Task 2 — `scripts/scalasemantic-mcp.sh` must not manufacture a project root value

**Owner target:** shell scripting. Depends on Task 1's investigation, same file.

- Audit every place `_project` is computed/threaded in `scalasemantic-mcp.sh`:
  `setup_main`'s `_project=$(CDPATH= cd -- "." && pwd)` default, the `--project`/`--root` flag
  override, and its use in `ensure_semanticdb_config`, `ensure_rules`, `ensure_steer_file`, and
  `write_client_configs`.
- Keep `_project` for the **setup-time filesystem operations** that legitimately need an absolute
  path right now (writing `SCALA_SEMANTIC_RULES.md`, `CLAUDE.md`/`AGENTS.md` steer files,
  `scala-semantic.sbt` into the project directory being set up) — those are one-time file-creation
  actions, not part of the per-launch `args`.
- The only change from Task 1 is what gets written into the **generated client config's `args`
  array** — never write `_project` there. Add a code comment-free guard/test (see Task 5) that fails
  CI if a future edit reintroduces an absolute path in generated `args`.
- `serve_main` itself already forwards `"$@"` verbatim to `java -jar "$JAR" "$@"` / `cs launch ...`
  — no change needed there; it correctly passes through whatever `args` the config gives it
  (which after Task 1 will be `.` or empty).

**Acceptance check:** grepping the script's `write_client_configs` function body shows `_proj_esc`
is no longer referenced in any `_entry`/`_fresh`/`_item` template string.

## Task 3 — New MCP tool: get/set the LLM's current working directory, stored statefully

**Owner target:** Scala, `mcp` module. This is the substantive code change.

### 3a. Verify `roots` capability first (spike, do not skip)

Before writing the workaround tool, spend a small timeboxed spike confirming whether Claude Code's
client actually sends `notifications/roots/list_changed` (or an equivalent) around
`EnterWorktree`/`ExitWorktree`:

- Add a temporary log line in `Mcp.handle` (`mcp/src/main/scala/.../mcp/Mcp.scala:91`) for any
  incoming method whose name starts with `"roots/"` or `"notifications/roots"`.
- Run a manual session: connect, call `EnterWorktree`, inspect the server's log file
  (`SCALASEMANTIC_LOG=1`) for any roots-related traffic.
- If confirmed working: prefer wiring root updates through that notification instead of (or in
  addition to) the explicit tool in 3b, and note this in the plan's Rollout Order.
- If not confirmed (expected, per current investigation): proceed with 3b as the only mechanism.
- Remove the temporary log line (or gate it behind `LogConfig`) once the spike concludes; don't ship
  debug-only logging permanently.

### 3b. Add `set_workspace_root` tool + mutable current-root state

- `Mcp.serve` (`Mcp.scala:221`) currently builds one `Analyzer`/`root` pair and calls
  `runLoop(root, rootPath, backend, log, logging)` once. Restructure so the root is **mutable**
  during the session:
  - Introduce a small holder, e.g. `class WorkspaceRoot(initial: Path)` with a `@volatile private var
    current: Path` and a `def get: Path` / `def set(p: Path): Unit`. Single-threaded stdio loop means
    no real concurrency, but `@volatile` costs nothing and avoids surprises if that ever changes.
  - `McpTools.all(az, root)` and every `McpToolsGroupA/B/C/D.tools(az, root)` currently close over an
    immutable `Analyzer`/`root` pair built once. Change the tool-construction call site so tools that
    need the root read it from `WorkspaceRoot.get` at call time instead of capturing it at
    construction — or, simpler: rebuild/re-fetch the `Analyzer` for the new root lazily (cache
    `Map[Path, Analyzer]` keyed by resolved root, matching the "lazy re-root" pattern already used
    for classpath in `classpath-refresh-plan.md`) and re-derive `tools` from the active entry on each
    dispatch in `Mcp.handle`.
  - Add the new tool itself (pattern-match existing `tool(name, description, params, required)(run)`
    helper in `McpToolsSupport.scala:345`):
    - name: `set_workspace_root`
    - params: single required `path` (string, absolute path preferred; resolve relative paths
      against the *current* root, not the original launch root)
    - behavior: validate the path exists and is a directory (reuse whatever existing path-validation
      helper the codebase has, e.g. near `argUri`/`argRefinedString` in `McpToolsSupport.scala`);
      update `WorkspaceRoot`; lazily (re)index that root if not already cached; return the resolved
      absolute path + whether it was newly indexed or already cached (cheap confirmation payload,
      not a full re-list of documents).
    - Also add a matching **read-only** `get_workspace_root` tool (no args) so the LLM/debugging can
      confirm current state without guessing — cheap to add alongside, useful for the smoke test in
      Task 5.
  - Every existing tool that currently takes `root` as a constructor-time value needs to instead
    resolve against `WorkspaceRoot.get` at call time. Scope this precisely: search
    (`mcp__scala-semantic__find_usages` on the `root` parameter of `McpToolsGroupA.tools` etc.) for
    every read site before changing signatures, to avoid missing one and leaving a stale-root path.
- Do **not** try to solve multi-agent/concurrent-session sharing here — this tool is deliberately
  single-session, single-process, sequential-switch scoped (per the earlier discussion: concurrent
  parallel worktree agents already get separate processes via `isolation: "worktree"` /
  `scripts/agent-run.sh`, so they never hit this shared-state problem in the first place).

**Acceptance check:** unit test in `McpSuite.scala` — call `set_workspace_root` with a second
temporary project directory containing distinct SemanticDB output, then call any read tool (e.g.
`document_outline`) and assert it now answers from the *new* root's index, not the launch root's.

## Task 4 — Add the calling rule to `SCALA_SEMANTIC_RULES.md`

**Owner target:** docs, one file, no code.

- Add a new numbered rule (after the existing 3 in `SCALA_SEMANTIC_RULES.md`) instructing: whenever
  the working directory changes for any reason (worktree switch, `cd` into a subproject, subagent
  dispatch into a different directory), call `set_workspace_root` with the new absolute path *before*
  issuing any other `scala-semantic` tool call, and call `get_workspace_root` if unsure of current
  state.
- Keep it short and imperative, matching the existing rule style (numbered, `**ALWAYS**`/`**NEVER**`
  emphasis already used in that file).
- This rule is discipline-based, not harness-enforced (per earlier discussion: no reliable
  hook-based guarantee exists today) — say so plainly in the rule or in this plan, so future readers
  don't assume it's bulletproof.
- Since `scalasemantic-mcp.sh`'s `ensure_rules`/`write_awk_libs` machinery generates/updates
  `SCALA_SEMANTIC_RULES.md` in *other* projects that install this server, update the heredoc template
  in `scalasemantic-mcp.sh` (`ensure_rules` function, the `cat > "$_rules" <<'EOF' ... EOF` block) to
  include the same rule, so newly-set-up projects get it too, not just this repo's own copy.

## Task 5 — One end-to-end smoke test

**Owner target:** Scala test, `mcp` module test sources, following the existing
`classpath-refresh-plan.md` "End-to-end launcher smoke test" pattern already in this repo
(`mcp/src/test/scala/.../mcp/McpSuite.scala` or a new sibling suite file).

- Spawn the real server process (same launcher boundary users hit: dev launcher or
  `scalasemantic-mcp.sh serve`), not just in-process `Mcp.handle` calls — the whole point is proving
  the *process* correctly switches root, matching how the classpath smoke test spawns a real
  subprocess.
- Steps:
  1. Create two temporary project roots (`rootA`, `rootB`), each with a distinct small Scala source
     file and its own compiled SemanticDB output (or use two fixture dirs already in the repo if
     suitable, to avoid a slow compile-in-test step).
  2. Launch the server pointed at `rootA` (default root, no baked path — proving Task 1/2 didn't
     regress).
  3. `initialize`, `notifications/initialized`, call a read tool (`document_outline` or
     `find_symbol`) for a symbol that only exists in `rootA` — assert found.
  4. Call `set_workspace_root` with `rootB`'s absolute path — assert the tool call succeeds and
     reports the new resolved root.
  5. Call the same read tool for a symbol that only exists in `rootB` — assert found, and assert the
     `rootA`-only symbol from step 3 is now *not* found (proving it actually switched, not just
     merged both).
  6. Call `get_workspace_root` — assert it reports `rootB`.
  7. Shut down by closing stdin, wait with timeout (same teardown pattern as the classpath smoke
     test).
- Keep it offline/deterministic: no network, no Maven/coursier resolution during the test — reuse
  whatever fixture/dev-launcher approach the classpath smoke test already established for this.

## Task 6 — Update docs

**Owner target:** docs, multiple files, no code.

- `docs/reference/tools.md` (or wherever the tool list lives — confirm exact file under
  `docs/reference/` or `docs/usage/`): add `set_workspace_root` / `get_workspace_root` to the tool
  list with a short description and the calling convention from Task 4.
- `CLAUDE.md` / this project's own steering: no change needed beyond the existing
  `@SCALA_SEMANTIC_RULES.md` include, since Task 4 already updates that file.
- Add a short section to this plan file's neighbor docs (or a new `docs/explanation/` entry) briefly
  explaining *why* this exists: link to the upstream Claude Code issues found during investigation
  (anthropics/claude-code#42282, #32747, #27881, #30906) as evidence this is a workaround for a
  harness-level gap, not a design preference — so a future reader doesn't try to "simplify" it away
  without understanding the constraint.
- Update `scripts/scalasemantic-mcp.sh`'s own usage/help text (the `usage()` function and top-of-file
  comment block) if the `setup`-generated config or rules content changed in a way a user should know
  about (it does, per Task 1/4).

## Rollout Order

1. Task 1 + Task 2 (config generation fix) — smallest, independently valuable even without Task 3;
   fixes the "shared baked absolute path" bug outright for any workflow that *does* relaunch the
   process per worktree (e.g. orchestrated subagents via `isolation: "worktree"`).
2. Task 3a (roots-capability spike) — cheap, informs whether Task 3b is the only path or can be
   simplified/supplemented.
3. Task 3b (stateful tool + mutable root) — the main code change.
4. Task 4 (rules doc) — depends on Task 3b's final tool name/signature being stable.
5. Task 5 (smoke test) — depends on Task 3b being complete; write it against the final tool
   contract, not before.
6. Task 6 (docs) — last, once tool names/behavior are final.

## Implementation Notes

- Implemented generated client config args as `["serve", "."]`; setup-time filesystem operations
  still use the absolute project path.
- Added `set_workspace_root` and `get_workspace_root`; the server keeps a per-process current root
  and lazily caches `Analyzer`/tool sets by resolved root path.
- Added the explicit cwd-change rule to this repo's `SCALA_SEMANTIC_RULES.md`, the setup-generated
  rules template, server initialize instructions, and the tool reference.
- Added a real launcher smoke test that starts `scripts/scalasemantic-mcp.sh serve`, queries one
  temporary SemanticDB root, switches to a second root, and verifies the old-root symbol is no
  longer visible.
- Did not rely on MCP `roots` notifications for this implementation. The explicit tools remain the
  supported workaround until client root-change behavior is independently verified.

## Out of Scope

- Solving concurrent multi-agent shared-process root switching (not needed: parallel workers already
  get isolated processes via existing orchestration design, see `[[multi-agent-orchestration]]`
  project memory).
- Any attempt to have hooks kill/restart the MCP subprocess directly — rejected earlier as fragile
  and fighting harness process ownership; not revisited here.
- Fixing the upstream Claude Code cwd-drift bugs themselves — out of this repo's control; only
  linked for context.
