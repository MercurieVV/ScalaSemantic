# Quickstart

Shortest path for any project.

1. **Install once for your user** — every Scala project on the machine gets the server, and there is
   no second step (needs only `java`):
   ```sh
   curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh
   ```

   Or install into a single project, so the launcher and config can be committed for your team —
   run this from the project root:
   ```sh
   curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh -s -- --project
   ```

   Prefer to read before running? It is one short shell script:
   ```sh
   curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh -o scalasemantic-mcp.sh
   less scalasemantic-mcp.sh && sh scalasemantic-mcp.sh
   ```

   The two differ in more than where the config lands. A **project** install also enables SemanticDB
   in your build, writes the tool-rules file and installs the Claude guard hook — so it is what a
   Scala repo wants when it has not been set up for SemanticDB yet. A **user** install only registers
   the server, for every project at once. Details and the per-client config paths:
   [Integration](integration.md).

   Cline and Roo have no user-level config location this installer can write (their global MCP
   settings live inside VS Code's own storage), so those two need a project install.
2. **Compile the project**:
   ```sh
   sbt compile
   ```
3. **Refresh live-buffer classpath metadata after dependency/build changes**:
   ```sh
   sbt scalaSemanticWriteClasspath
   ```

For manual configurations or other integration options (Scala CLI remote script, plain `java -jar`), see [Integration](integration.md).

## Opening a directory that is not a Scala project

With a user install the server is registered everywhere, including your Python and Node repos. That
is harmless: it starts, connects, and lists its tools normally, and each tool call answers
`could not detect a Scala project root at or above …` rather than a confident empty result. Point it
somewhere real with the `set_workspace_root` tool, or pass a root explicitly
(`scalasemantic-mcp serve /path/to/project`). See
[ADR 0004](../adr/0004-single-launcher-script-and-user-scope-install.md).

## Claude Code: the guard hook

`setup --rwhook-local` installs `.claude/hooks/scala-semantic-guard.sh` and registers it as a
`PreToolUse` hook, so text tools (`Read`, `Grep`, `Glob`, and shell `grep`/`rg`/`cat`/`sed`/…) are
**denied** on `.scala` files and the agent is told which MCP tool to use instead. It fails open when
no `*.semanticdb` has been emitted yet, when the MCP server is not configured for the project, or
when neither `jq` nor `python3` is available. A shell command carrying
`# semantic-fallback: <reason>` is always allowed, and appended to `.claude/semantic-fallback.log`.

The same hook also fires when the agent **edits** a Scala source. That is not denied — it prints a
reminder to work on the annotated buffer instead, so the edit is made with the compiler's inferred
types and implicits in view:

```
annotated_source(uri, format="compilable", sentinel=true)   -> buffer + sha256
edit that buffer, leaving the /*SEM:...:SEM*/ blocks in place
annotated_source(uri, write=<edited text>, baseHash=<sha256>)
```

Write mode strips the SEM blocks before saving, and `baseHash` rejects the write if the file changed
meanwhile. Pass `--strict-edits` to setup to make Scala edits a denial instead of a reminder.

The hook is opt-in — a plain `setup` installs none, since it changes how every later session in
that directory reads Scala:

```sh
./scalasemantic-mcp.sh setup --rwhook-local    # this project
./scalasemantic-mcp.sh setup --rwhook-user     # ~/.claude, every project you open
./scalasemantic-mcp.sh setup --rw-hook-remove  # remove it from both
```

A plain run still keeps a hook that is already installed up to date. Background:
[ADR 0001](../adr/0001-claude-code-guard-hook.md).

### Checking that the guard actually fires

Because the hook fails open silently, a broken install looks exactly like a healthy one. `doctor`
re-runs every condition the hook checks and prints the verdict:

```
./scalasemantic-mcp.sh doctor            # or: doctor --project /path/to/project
scalasemantic-mcp: doctor: /path/to/project
  ok    guard hook installed - .claude/hooks/scala-semantic-guard.sh
  ok    guard hook up to date - matches this version of the launcher
  ok    guard hook registered - PreToolUse entry in .claude/settings.json
  ok    MCP server configured - found in .mcp.json / .claude/settings*.json
  ok    SemanticDB index - out/core/semanticDbData.dest/classes/META-INF/semanticdb/...
  ok    JSON reader for the hook - jq
```

It exits 1 when the guard is installed but any of those conditions fails, i.e. when it would let
every text tool through. `setup` runs the same check at the end of an install and reports only the
failures.
