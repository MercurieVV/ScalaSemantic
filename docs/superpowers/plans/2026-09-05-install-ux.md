# Install UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse ScalaSemantic's install to two curl one-liners served by a single shell script, register the MCP server user-wide by default, and stop a global registration from showing a broken server in every non-Scala repo.

**Architecture:** `scripts/scalasemantic-mcp.sh` becomes launcher + installer: when it cannot resolve its own path (i.e. it was piped to `sh`) it downloads itself to the install location and re-execs. Every install *decision* lives in the existing `launcher/` Scala module inside the fat jar — `LauncherSetup` gains a `Scope`, `LauncherClientConfigs` gains per-client user-level config paths. Separately, an unresolved project root stops being a startup `sys.exit(1)` and becomes a per-tool-call error, so a user-scope registration idles harmlessly in non-Scala projects.

**Tech Stack:** POSIX `sh`; Scala 3.8.4; Mill 1.1.7; munit 1.2.3; upickle 4.2.1; scala-cli for the end-to-end test script.

**Spec:** `docs/superpowers/specs/2026-09-05-install-ux-design.md`

## Global Constraints

- Scala 3.8.4, Mill 1.1.7. Build with `./mill`, never `sbt` — `build.sbt` and `project/` are deleted.
- Package base `com.github.mercurievv.scalasemantic`. The `launcher/` module's types are `private[scalasemantic]`; keep them so.
- `scripts/scalasemantic-mcp.sh` must stay POSIX `sh` (no bashisms) and ≤ 100 lines. If a change needs to know about an MCP client, a config format, or a build tool, it belongs in Scala, not in the script.
- New scripts under `scripts/` are scala-cli scripts (`.sc`, `#!/usr/bin/env scala-cli` shebang, `//> using scala 3.8.4`), per CLAUDE.md.
- Never use text tools (`grep`/`cat`/`sed`) on `.scala` sources — use the `scala-semantic` MCP tools. Shell-out to text tools on `.sh`, `.md`, `.json` is fine.
- Never add `Co-Authored-By` trailers to commit messages.
- Do not pre-run `./mill compile`/`test`/`scalafmt` before `tree2m`; the pre-push hook runs them.
- Commit messages use Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`).
- The MCP server name written into every client config is exactly `scala-semantic`.
- The server's config `args` are exactly `["serve", "."]`.

---

### Task 1: End-to-end install test harness (RED)

Writes the failing acceptance test that Tasks 2–4 make pass. This task's deliverable is a test that
fails for the right reason. It is local-only and never runs in CI.

**Files:**
- Create: `scripts/test-install.sc`

**Interfaces:**
- Consumes: nothing.
- Produces: `scripts/test-install.sc`, runnable as `scala-cli scripts/test-install.sc -- <mode>` where
  `<mode>` is `user` or `project`; exits 0 on success, 1 with a diagnostic on failure. Task 5 extends
  the same file with the client-drive and negative cases.

- [ ] **Step 1: Write the failing test**

Create `scripts/test-install.sc`:

```scala
#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// End-to-end install test for the ScalaSemantic MCP one-liners. LOCAL ONLY — Task 5 adds steps
// that drive real `claude`/`codex`/`agy` binaries, which need authentication, so this is never
// wired into CI. Run from the repo root:
//
//   scala-cli scripts/test-install.sc -- user
//   scala-cli scripts/test-install.sc -- project
//
// Each run is hermetic: a fresh temp directory is used as HOME, so nothing touches the developer's
// real configs, and the run starts by clearing every path it will later assert on.

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object TestInstall {

  val RepoRoot: Path = Paths.get(".").toAbsolutePath.normalize()
  val Installer: Path = RepoRoot.resolve("scripts/scalasemantic-mcp.sh")

  def fail(msg: String): Nothing = {
    System.err.println(s"FAIL: $msg")
    sys.exit(1)
  }

  def check(cond: Boolean, msg: String): Unit = if (!cond) fail(msg)

  def rmTree(p: Path): Unit =
    if (Files.exists(p))
      Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(f => Files.delete(f))

  /** Runs a command, returning (exitCode, stdout, stderr). */
  def run(cmd: Seq[String], cwd: Path, env: Map[String, String]): (Int, String, String) = {
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    val e = pb.environment()
    env.foreach { case (k, v) => e.put(k, v) }
    val proc = pb.start()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  /** A minimal but real sbt project with SemanticDB on and one distinctively-named symbol. */
  def fixtureProject(parent: Path): Path = {
    val dir = parent.resolve("fixture-project")
    Files.createDirectories(dir.resolve("src/main/scala"))
    Files.writeString(
      dir.resolve("build.sbt"),
      """|ThisBuild / scalaVersion := "3.8.4"
         |ThisBuild / semanticdbEnabled := true
         |lazy val root = (project in file("."))
         |""".stripMargin
    )
    Files.writeString(
      dir.resolve("project/build.properties"),
      "sbt.version=1.10.7\n"
    )
    Files.writeString(
      dir.resolve("src/main/scala/Fixture.scala"),
      """|object Fixture:
         |  def zzUniqueFixtureSymbol(n: Int): Int = n + 1
         |""".stripMargin
    )
    dir
  }

  /** Config files each mode is expected to create, relative to HOME (user) or project (project). */
  def expectedConfigs(mode: String): Seq[String] =
    if (mode == "user")
      Seq(".claude.json", ".codex/config.toml", ".gemini/settings.json", ".continue/config.yaml")
    else
      Seq(
        ".mcp.json",
        ".codex/config.toml",
        ".gemini/settings.json",
        ".continue/config.yaml",
        ".cline/mcp.json",
        ".roo/mcp.json",
        ".agents/mcp_config.json"
      )

  def main(args: Array[String]): Unit = {
    val mode = args.headOption.getOrElse(fail("usage: test-install.sc -- user|project"))
    check(mode == "user" || mode == "project", s"unknown mode '$mode'")
    check(Files.exists(Installer), s"installer not found at $Installer")

    val sandbox = Files.createTempDirectory("scalasemantic-install-test")
    val home = sandbox.resolve("home")
    Files.createDirectories(home)
    val project = fixtureProject(sandbox)
    val env = Map("HOME" -> home.toString, "SCALASEMANTIC_TEST" -> "1")

    val launcher =
      if (mode == "user") home.resolve(".local/bin/scalasemantic-mcp")
      else project.resolve("scalasemantic-mcp.sh")
    val dataDir = home.resolve(".local/share/scalasemantic-mcp")
    val configBase = if (mode == "user") home else project
    val configs = expectedConfigs(mode).map(configBase.resolve)

    // 1. Clear.
    rmTree(launcher)
    rmTree(dataDir)
    configs.foreach(rmTree)

    // 2. Assert cleared — a stale install must never make a later assertion pass.
    check(!Files.exists(launcher), s"launcher not cleared: $launcher")
    check(!Files.exists(dataDir), s"data dir not cleared: $dataDir")
    configs.foreach(c => check(!Files.exists(c), s"config not cleared: $c"))
    println("[ok] cleared")

    // 3. Install, through a pipe, so the script's self-install path is exercised.
    val modeFlag = if (mode == "user") "" else " -- --project"
    val installCmd =
      Seq("sh", "-c", s"cat ${Installer.toString} | sh$modeFlag")
    val (code, out, err) = run(installCmd, project, env)
    check(code == 0, s"install exited $code\n--- stdout ---\n$out\n--- stderr ---\n$err")
    println("[ok] installed")

    // 4. Assert installed.
    check(Files.exists(launcher), s"launcher missing after install: $launcher")
    check(Files.isExecutable(launcher), s"launcher not executable: $launcher")
    val jars = Files.list(dataDir).iterator().asScala.filter(_.toString.endsWith(".jar")).toVector
    check(jars.nonEmpty, s"no jar cached under $dataDir")

    val expectedCommand =
      if (mode == "user") launcher.toString else "./scalasemantic-mcp.sh"
    configs.foreach { c =>
      check(Files.exists(c), s"config missing after install: $c")
      val text = Files.readString(c)
      check(text.contains("scala-semantic"), s"server name missing in $c:\n$text")
      check(text.contains(expectedCommand), s"expected command '$expectedCommand' missing in $c:\n$text")
      check(text.contains("serve"), s"'serve' arg missing in $c:\n$text")
    }
    println("[ok] configs written")

    // 6. Idempotent: a second install must not change a byte, must not duplicate the entry, and
    //    must preserve an unrelated server planted beforehand.
    val claudeConfig = configs.head
    val before = Files.readString(claudeConfig)
    val (code2, out2, err2) = run(installCmd, project, env)
    check(code2 == 0, s"second install exited $code2\n$out2\n$err2")
    val after = Files.readString(claudeConfig)
    check(before == after, s"install is not idempotent:\n--- before ---\n$before\n--- after ---\n$after")
    check(
      after.sliding("scala-semantic".length).count(_ == "scala-semantic") == 1,
      s"duplicate scala-semantic entry after re-install:\n$after"
    )
    println("[ok] idempotent")

    // 7. Teardown.
    rmTree(sandbox)
    check(!Files.exists(sandbox), s"sandbox not removed: $sandbox")
    println(s"[PASS] install test ($mode)")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli scripts/test-install.sc -- user`
Expected: FAIL. The current script has no self-install and no `--scope`, so the run fails at
`launcher missing after install` (or the install command exits non-zero). Confirm the failure is one
of those, not a syntax error in the test itself.

- [ ] **Step 3: Commit the RED test**

```bash
git add scripts/test-install.sc
git commit -m "test: add end-to-end install acceptance test (currently failing)"
```

---

### Task 2: `Scope` and user-level config paths in the launcher module

**Files:**
- Modify: `launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherSetup.scala`
- Modify: `launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherClientConfigs.scala`
- Modify: `launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherMessages.scala`
- Test: `launcher/src/test/scala/com/github/mercurievv/scalasemantic/LauncherClientConfigsSuite.scala`
- Create: `launcher/src/test/scala/com/github/mercurievv/scalasemantic/LauncherSetupSuite.scala`

**Interfaces:**
- Consumes: existing `LauncherSetup.Options`, `LauncherClientConfigs.write(project, opts)`,
  `LauncherConfigMerge.mergeJson/mergeToml/mergeYaml`.
- Produces:
  - `enum LauncherScope { case User, Project }` in `LauncherSetup.scala`.
  - `LauncherSetup.Options` gains `scope: LauncherScope = LauncherScope.Project` and
    `home: Path = Path.of(sys.props.getOrElse("user.home", "."))`.
  - `LauncherSetup.parse` becomes `private[scalasemantic] def parse(args: List[String]): Options`
    and accepts `--scope user|project`.
  - `LauncherClientConfigs.targetPathFor(opts: LauncherSetup.Options, client: String): Option[Path]`
    — the absolute file to write for that client under that scope, `None` when the client has no
    config for that scope.

`Options.home` exists so tests can point the user scope at a temp directory instead of the real
`$HOME`. The shell never passes it; only tests do.

- [ ] **Step 1: Write the failing tests**

Create `launcher/src/test/scala/com/github/mercurievv/scalasemantic/LauncherSetupSuite.scala`:

```scala
package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

class LauncherSetupSuite extends munit.FunSuite:

  test("parse defaults to project scope") {
    assertEquals(LauncherSetup.parse(Nil).scope, LauncherScope.Project)
  }

  test("parse accepts --scope user") {
    assertEquals(
      LauncherSetup.parse(List("--scope", "user")).scope,
      LauncherScope.User
    )
  }

  test("parse accepts --scope project") {
    assertEquals(
      LauncherSetup.parse(List("--scope", "project")).scope,
      LauncherScope.Project
    )
  }

  test("targetPathFor resolves user-scope paths under home") {
    val home = Files.createTempDirectory("launcher-home")
    try
      val opts = LauncherSetup.Options(scope = LauncherScope.User, home = home)
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "claude"),
        Some(home.resolve(".claude.json"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "codex"),
        Some(home.resolve(".codex/config.toml"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "gemini"),
        Some(home.resolve(".gemini/settings.json"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "continue"),
        Some(home.resolve(".continue/config.yaml"))
      )
    finally
      Files.walk(home).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  test("targetPathFor resolves project-scope paths under the project") {
    val project = Files.createTempDirectory("launcher-project")
    try
      val opts = LauncherSetup.Options(project = project, scope = LauncherScope.Project)
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "claude"),
        Some(project.resolve(".mcp.json"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "cline"),
        Some(project.resolve(".cline/mcp.json"))
      )
    finally
      Files.walk(project).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  test("targetPathFor returns None for a client with no user-scope config") {
    val home = Files.createTempDirectory("launcher-home-none")
    try
      val opts = LauncherSetup.Options(scope = LauncherScope.User, home = home)
      // Whichever clients ship with userPath = None must resolve to None rather than a guess.
      LauncherClientConfigs.clientsWithoutUserScope.foreach { client =>
        assertEquals(
          LauncherClientConfigs.targetPathFor(opts, client),
          None,
          s"expected no user-scope path for '$client'"
        )
      }
    finally
      Files.walk(home).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  test("targetPathFor returns None for an unknown client in either scope") {
    val opts = LauncherSetup.Options()
    assertEquals(LauncherClientConfigs.targetPathFor(opts, "notaclient"), None)
  }
```

Append to `launcher/src/test/scala/com/github/mercurievv/scalasemantic/LauncherClientConfigsSuite.scala`:

```scala
  test("write under user scope emits an absolute command and writes under home") {
    withTempProject { home =>
      val launcher = home.resolve(".local/bin/scalasemantic-mcp")
      Files.createDirectories(launcher.getParent)
      Files.writeString(launcher, "#!/bin/sh\n")
      val opts = LauncherSetup.Options(
        project = home,
        client = "claude",
        command = launcher.toAbsolutePath.toString,
        scope = LauncherScope.User,
        home = home
      )
      LauncherClientConfigs.write(home, opts)
      val written = Files.readString(home.resolve(".claude.json"))
      assert(
        written.contains(launcher.toAbsolutePath.toString),
        s"expected the absolute launcher path in a user-scope config, got:\n$written"
      )
      assert(
        !Files.exists(home.resolve(".mcp.json")),
        "user scope must not write a project .mcp.json"
      )
    }
  }
```

Note the deliberate asymmetry in that test: `project` and `home` are the same temp directory, so
`relativizeCommand` would rewrite the command to `./.local/bin/scalasemantic-mcp` if user-scope
writes were still routed through project relativization. The assertion on the absolute path is
what catches that.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mill launcher.test`
Expected: FAIL to compile — `LauncherScope`, `Options.scope`, `Options.home`,
`LauncherClientConfigs.targetPathFor` and `clientsWithoutUserScope` do not exist, and
`LauncherSetup.parse` is `private`.

- [ ] **Step 3: Add the scope type and options**

In `LauncherSetup.scala`, add above the `object`:

```scala
private[scalasemantic] enum LauncherScope:
  case User, Project
```

Change `Options` to:

```scala
  final case class Options(
      project: Path = Path.of(".").toAbsolutePath.normalize(),
      client: String = "all",
      command: String = sys.env.getOrElse("SCALASEMANTIC_LAUNCHER", "scalasemantic-mcp"),
      skipSemanticdbConfig: Boolean = false,
      guard: Boolean = true,
      scope: LauncherScope = LauncherScope.Project,
      home: Path = Path.of(sys.props.getOrElse("user.home", ".")).toAbsolutePath.normalize()
  )
```

Change `private def parse` to `private[scalasemantic] def parse` and add this case immediately
before the `case ("--help" | "-h") :: _` case:

```scala
          case "--scope" :: value :: tail =>
            val scope = value.trim.toLowerCase match
              case "user"    => LauncherScope.User
              case "project" => LauncherScope.Project
              case bad =>
                LauncherMessages.err(s"unknown --scope value: $bad (expected user or project)")
                LauncherMessages.usage(2)
            loop(tail, opts.copy(scope = scope))
```

Change `setup` so project-only work is skipped under user scope:

```scala
  def setup(rawArgs: List[String]): Unit =
    val opts = parse(rawArgs)
    opts.scope match
      case LauncherScope.User =>
        LauncherClientConfigs.write(opts.project, opts)
      case LauncherScope.Project =>
        val project = opts.project
        Files.createDirectories(project)
        ensureSemanticdbConfig(project, opts.skipSemanticdbConfig)
        LauncherRules.ensure(project, opts.client)
        LauncherClientConfigs.write(project, opts)
        if opts.guard then LauncherGuardHook.install(project, opts.client)
        ensureClasspathMetadataDir(project)
```

- [ ] **Step 4: Add scope-aware targets**

In `LauncherClientConfigs.scala`, change `Target` and `targetFor`, and add the two new members:

```scala
  private final case class Target(
      relPath: String,
      userPath: Option[String],
      fmt: Fmt,
      extraJson: Seq[(String, String)]
  )
```

Every existing `Target(...)` construction gains a `userPath` argument in second position:

| client | `relPath` | `userPath` |
|---|---|---|
| codex | `".codex/config.toml"` | `Some(".codex/config.toml")` |
| claude | `".mcp.json"` | `Some(".claude.json")` |
| gemini | `".gemini/settings.json"` | `Some(".gemini/settings.json")` |
| antigravity | `".agents/mcp_config.json"` | see Step 5 |
| cline | `".cline/mcp.json"` | see Step 5 |
| roo | `".roo/mcp.json"` | see Step 5 |
| continue | `".continue/config.yaml"` | `Some(".continue/config.yaml")` |
| generic | `".mcp.json"` | `None` |

Add:

```scala
  private[scalasemantic] def targetPathFor(
      opts: LauncherSetup.Options,
      client: String
  ): Option[Path] =
    targetFor(client).flatMap { target =>
      opts.scope match
        case LauncherScope.Project => Some(opts.project.resolve(target.relPath))
        case LauncherScope.User    => target.userPath.map(opts.home.resolve)
    }

  /** Clients that ship without a known user-level MCP config; `--scope user` skips them. */
  private[scalasemantic] def clientsWithoutUserScope: Seq[String] =
    Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity", "generic")
      .filter(c => targetFor(c).exists(_.userPath.isEmpty))
```

Rewrite `write` to route through `targetPathFor` and to relativize only under project scope:

```scala
  def write(project: Path, opts: LauncherSetup.Options): Unit =
    val command = opts.scope match
      case LauncherScope.Project => relativizeCommand(project, opts.command)
      case LauncherScope.User    => opts.command
    val argv = Seq(command, "serve", ".")
    val clients =
      if opts.client.trim.toLowerCase == "all" then
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      else Seq(opts.client)
    clients.foreach { client =>
      (targetFor(client), targetPathFor(opts, client)) match
        case (Some(target), Some(out)) =>
          Option(out.getParent).foreach(Files.createDirectories(_))
          val existing = if Files.exists(out) then Some(Files.readString(out)) else None
          val merged =
            target.fmt match
              case JsonFmt =>
                LauncherConfigMerge.mergeJson(existing, ServerName, argv, target.extraJson)
              case TomlFmt =>
                LauncherConfigMerge.mergeToml(existing, ServerName, argv)
              case YamlFmt =>
                LauncherConfigMerge.mergeYaml(existing, ServerName, argv)
          Files.writeString(out, merged)
          LauncherMessages.err(s"wrote $out")
        case (Some(_), None) =>
          LauncherMessages.err(s"skipped '$client': no user-level MCP config location is known")
        case (None, _) =>
          LauncherMessages.err(s"unsupported client '$client'")
    }
```

- [ ] **Step 5: Settle the three unverified user paths**

For `antigravity`, `cline` and `roo`, find each client's documented user-level MCP config location
(their own docs or repository README). For each one confirmed, set `userPath = Some("<path>")`
relative to `$HOME`. For any that cannot be confirmed from documentation, set `userPath = None` —
shipping a guessed path writes a file nobody reads while telling the user they are installed.

Record the outcome in the ADR written in Task 6, listing which of the three got a path and which
were left as `None`, with the source consulted.

Then update `expectedConfigs("user")` in `scripts/test-install.sc` to include any client that gained
a user path, so the acceptance test asserts on it.

- [ ] **Step 6: Update the usage text**

In `LauncherMessages.usage`, change the first usage line and add the scope note:

```scala
            |  scalasemantic-mcp setup [--scope user|project] [--client claude|codex|gemini|cline|roo|continue|antigravity|all] [--project DIR] [--no-guard]
            |  scalasemantic-mcp serve <semanticdb-root> [classpath-file] [--log] [--log-output]
            |
            |--scope project (default) writes config into the project directory and also configures
            |SemanticDB, the tool rules file and the Claude guard hook. --scope user writes only the
            |MCP registration, into the per-user config of each client that has one.
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mill launcher.test`
Expected: PASS, including the pre-existing `relativizeCommand` tests, which must be untouched.

- [ ] **Step 8: Commit**

```bash
git add launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherSetup.scala \
        launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherClientConfigs.scala \
        launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherMessages.scala \
        launcher/src/test/scala/com/github/mercurievv/scalasemantic/LauncherSetupSuite.scala \
        launcher/src/test/scala/com/github/mercurievv/scalasemantic/LauncherClientConfigsSuite.scala \
        scripts/test-install.sc
git commit -m "feat(launcher): add --scope user|project and user-level client config paths"
```

---

### Task 3: One self-installing script; delete the other two

**Files:**
- Modify: `scripts/scalasemantic-mcp.sh`
- Delete: `scripts/install.sh`
- Delete: `scripts/scalasemantic-mcp.scala`

**Interfaces:**
- Consumes: `scalasemantic-mcp install --scope user|project [--project DIR]` from Task 2.
- Produces: the two one-liners. Piped to `sh` with no arguments → user-scope install. Piped with
  `-- --project` → project-scope install using `$(pwd)` as the project. Invoked as an installed
  file with any other arguments → unchanged launcher behaviour (`exec java -jar "$JAR" "$@"`).

- [ ] **Step 1: Replace the script**

Write `scripts/scalasemantic-mcp.sh`:

```sh
#!/usr/bin/env sh
# ScalaSemantic MCP: launcher and installer in one script. SCALASEMANTIC_SELF_MARKER
#
#   curl -fsSL <raw-url> | sh                 # install for this user (all projects)
#   curl -fsSL <raw-url> | sh -s -- --project # install into the current project
#   scalasemantic-mcp serve .                 # run the server (what MCP clients invoke)
#
# It keeps the self-updating fat-jar cache and forwards everything else to the jar, which owns all
# install logic (client configs, scopes, SemanticDB setup, guard hook).
set -eu

REPO="MercurieVV/ScalaSemantic"
DATA="${SCALASEMANTIC_HOME:-$HOME/.local/share/scalasemantic-mcp}"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
RAW_URL="https://raw.githubusercontent.com/$REPO/master/scripts/scalasemantic-mcp.sh"

fetch_stdout() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
  else echo "scalasemantic-mcp: need curl or wget on PATH" >&2; return 1
  fi
}

fetch_file() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else echo "scalasemantic-mcp: need curl or wget on PATH" >&2; return 1
  fi
}

# Piped through `sh`, $0 is "sh" and there is no file to exec: install a copy and hand over to it.
resolve_self() {
  [ -n "${0:-}" ] && [ -f "$0" ] || return 1
  grep -q SCALASEMANTIC_SELF_MARKER "$0" 2>/dev/null || return 1
  echo "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
}

self_install() {
  if [ "$1" = project ]; then dest="$(pwd)/scalasemantic-mcp.sh"
  else mkdir -p "$BIN_DIR"; dest="$BIN_DIR/scalasemantic-mcp"
  fi
  echo "scalasemantic-mcp: installing launcher to $dest" >&2
  fetch_file "$RAW_URL" "$dest.tmp"
  mv -f "$dest.tmp" "$dest"
  chmod +x "$dest"
  echo "$dest"
}

newest_cached() { ls -t "$DATA"/scalasemantic-mcp-*.jar 2>/dev/null | head -1 || true; }

resolve_tag() {
  if [ -n "${SCALASEMANTIC_VERSION:-}" ]; then printf '%s' "$SCALASEMANTIC_VERSION"; return 0; fi
  fetch_stdout "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
    | grep '"tag_name"' | head -1 | cut -d'"' -f4 || true
}

download_release() {
  jar="$DATA/scalasemantic-mcp-$1.jar"
  [ -f "$jar" ] && return 0
  echo "scalasemantic-mcp: downloading $1 ..." >&2
  fetch_file "https://github.com/$REPO/releases/download/$1/scalasemantic-mcp.jar" "$jar.tmp" || {
    rm -f "$jar.tmp"; return 1
  }
  mv -f "$jar.tmp" "$jar"
}

background_fetch() {
  lock="$DATA/.bgfetch.lock"
  if mkdir "$lock" 2>/dev/null; then
    trap 'rmdir "$lock" 2>/dev/null || true' EXIT INT TERM
    tag=$(resolve_tag); [ -n "$tag" ] && download_release "$tag" || true
  fi
}

jar_to_run() {
  cached=$(newest_cached)
  if [ -z "${SCALASEMANTIC_VERSION:-}" ] && [ -n "$cached" ]; then
    ( "$SELF" --bg-fetch >/dev/null 2>&1 </dev/null & ) >/dev/null 2>&1 || true
    printf '%s' "$cached"; return 0
  fi
  tag=$(resolve_tag)
  [ -n "$tag" ] && download_release "$tag" || true
  jar="$DATA/scalasemantic-mcp-${tag:-unknown}.jar"
  if [ -f "$jar" ]; then printf '%s' "$jar"; return 0; fi
  cached=$(newest_cached)
  [ -n "$cached" ] || { echo "scalasemantic-mcp: no release and no cached jar" >&2; exit 1; }
  echo "scalasemantic-mcp: offline - using cached $(basename "$cached")" >&2
  printf '%s' "$cached"
}

mkdir -p "$DATA"

MODE=""
case "${1:-}" in
  --project) MODE=project; shift ;;
  --user)    MODE=user;    shift ;;
esac

if SELF=$(resolve_self); then :; else
  # Bootstrap: no file to exec. Install ourselves, then re-enter with an explicit mode so the
  # second pass always installs rather than falling through to serve.
  DEST=$(self_install "${MODE:-user}")
  exec "$DEST" "--${MODE:-user}" "$@"
fi

case "${1:-}" in
  --bg-fetch) background_fetch; exit 0 ;;
  --prefetch) shift; jar=$(jar_to_run)
              echo "scalasemantic-mcp: prefetched $(basename "$jar")" >&2; exit 0 ;;
esac

JAR=$(jar_to_run)
SCALASEMANTIC_LAUNCHER="$SELF"
export SCALASEMANTIC_LAUNCHER

if [ "$MODE" = project ]; then
  exec java -jar "$JAR" install --scope project --project "$(pwd)" "$@"
elif [ "$MODE" = user ]; then
  exec java -jar "$JAR" install --scope user "$@"
fi

exec java -jar "$JAR" "$@"
```

Two details that are easy to get wrong:

- `SCALASEMANTIC_LAUNCHER` is exported before the install exec, so `LauncherSetup.Options.command`
  picks up this script's absolute path. Under user scope Task 2 no longer relativizes it, which is
  what puts the absolute path into `~/.claude.json`.
- `--project` in the shell is a *mode flag with no value*, while the jar's `--project` takes a
  directory. The script translates one into the other (`--project "$(pwd)"`); the jar never sees a
  valueless `--project`.

- [ ] **Step 2: Delete the two superseded files**

```bash
git rm scripts/install.sh scripts/scalasemantic-mcp.scala
```

- [ ] **Step 3: Check the script is POSIX and within budget**

Run: `sh -n scripts/scalasemantic-mcp.sh && wc -l scripts/scalasemantic-mcp.sh`
Expected: no syntax errors, line count ≤ 100. If `shellcheck` is installed, also run
`shellcheck -s sh scripts/scalasemantic-mcp.sh` and fix anything it reports.

- [ ] **Step 4: Run the acceptance test from Task 1**

Run: `./mill mcp.assembly` first (the test needs a jar; if no release is reachable, set
`SCALASEMANTIC_VERSION` to a published tag), then:

```
scala-cli scripts/test-install.sc -- user
scala-cli scripts/test-install.sc -- project
```

Expected: both PASS through the "idempotent" step.

- [ ] **Step 5: Verify nothing else references the deleted files**

Run: `grep -rn "install\.sh\|scalasemantic-mcp\.scala" --include='*.md' --include='*.sh' --include='*.sc' --include='*.yml' . | grep -v '^./out/'`
Expected: only hits in `docs/`, which Task 6 rewrites. Fix any hit in `.github/workflows/` or
`scripts/` now, in this task.

- [ ] **Step 6: Commit**

```bash
git add scripts/scalasemantic-mcp.sh
git commit -m "feat(install): single self-installing launcher script, two one-liners"
```

---

### Task 4: Unresolved project root fails at tool-call time, not startup

**Files:**
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/Main.scala:31-42`
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/Mcp.scala:467-495`
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/ProjectRootDiscoverySuite.scala`

**Interfaces:**
- Consumes: `ProjectRootDiscovery.resolveDefaultRoot(cwd: Path, skipCheck: Boolean): Either[String, Path]`
  (unchanged), `Mcp.serve(root: String, classpath: Option[String], logging: LogConfig): Unit`.
- Produces: `Mcp.serve` gains a fourth parameter
  `unresolvedRoot: Option[String] = None`. When `Some(error)`, the server indexes an empty scratch
  directory instead of cwd, and every tool except `set_workspace_root` and `get_workspace_root`
  returns `error` as its result text.

Wrapping the real tool list (rather than inventing a parallel one) keeps tool names and JSON schemas
identical in both states, so a client sees the same server either way. `set_workspace_root` stays
live because it is the in-band fix: an agent that gets the error can point the server at the right
directory without restarting it.

- [ ] **Step 1: Write the failing tests**

Append to `mcp/src/test/scala/com/github/mercurievv/scalasemantic/ProjectRootDiscoverySuite.scala`:

```scala
  test("an empty directory yields no root") {
    val dir = Files.createTempDirectory("no-markers")
    try assertEquals(ProjectRootDiscovery.find(dir), None)
    finally Files.delete(dir)
  }

  test("resolveDefaultRoot reports the failure instead of throwing") {
    val dir = Files.createTempDirectory("no-markers-resolve")
    try
      val result = ProjectRootDiscovery.resolveDefaultRoot(dir, skipCheck = false)
      assert(result.isLeft, s"expected a Left for a directory with no build marker, got $result")
      val message = result.left.getOrElse("")
      assert(
        message.contains("could not detect a Scala project root"),
        s"error text changed; the install test asserts on it:\n$message"
      )
    finally Files.delete(dir)
  }
```

Create `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/UnresolvedRootSuite.scala`:

```scala
package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files

class UnresolvedRootSuite extends munit.FunSuite:

  test("tools are wrapped with the error, except the workspace-root escape hatches") {
    val scratch = Files.createTempDirectory("unresolved-root")
    try
      val message = "could not detect a Scala project root at or above '/tmp/x'"
      val tools = Mcp.unresolvedRootTools(scratch, message)
      assert(tools.nonEmpty, "expected the full tool list even with an unresolved root")
      val names = tools.map(_.name).toSet
      assert(names.contains("find_symbol"), s"tool list looks wrong: $names")
      assert(names.contains("set_workspace_root"), s"escape hatch missing: $names")

      val findSymbol = tools.find(_.name == "find_symbol").get
      val result = ujson.write(findSymbol.run(ujson.Obj("name" -> "Anything")))
      assert(
        result.contains("could not detect a Scala project root"),
        s"expected the discovery error from a wrapped tool, got:\n$result"
      )

      val getRoot = tools.find(_.name == "get_workspace_root").get
      val rootResult = ujson.write(getRoot.run(ujson.Obj()))
      assert(
        !rootResult.contains("could not detect a Scala project root"),
        s"get_workspace_root must not be wrapped, got:\n$rootResult"
      )
    finally
      Files.walk(scratch).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mill mcp.test`
Expected: FAIL to compile — `Mcp.unresolvedRootTools` does not exist.

- [ ] **Step 3: Add `unresolvedRootTools` and the `serve` parameter**

In `Mcp.scala`, add near `toolsFor`:

```scala
  /** The full tool surface, but answering with `message` instead of querying an index. Used when
    * the launch directory is not a Scala project: the server stays connectable (see ADR-0004) and
    * says why on each call, rather than exiting and showing as a failed server in every non-Scala
    * repository. `set_workspace_root` stays live — it is the in-band fix.
    */
  private[scalasemantic] def unresolvedRootTools(scratch: Path, message: String): List[Tool] =
    val passthrough = Set("set_workspace_root", "get_workspace_root")
    val base = buildState(scratch, None, _ => ()).tools
    base.map { tool =>
      if passthrough.contains(tool.name) then tool
      else tool.copy(run = _ => textBlock(message))
    }
```

Change `serve`'s signature and body:

```scala
  def serve(
      root: String,
      classpath: Option[String] = None,
      logging: LogConfig = LogConfig.off,
      unresolvedRoot: Option[String] = None
  ): Unit =
```

and, inside, replace the state setup / `runLoop` call so the unresolved case never indexes cwd:

```scala
      unresolvedRoot match
        case Some(message) =>
          val scratch = Files.createTempDirectory("scalasemantic-unresolved")
          scratch.toFile.deleteOnExit()
          currentLog(s"root unresolved: $message")
          runLoopWithTools(unresolvedRootTools(scratch, message), currentLog, logging)
        case None =>
          runLoop(root, rootPath, initialState.pcSelector, currentLog, logging)
```

Extract the last five lines of `runLoop` (reader, out, lines, onCall, `process(...)`) into
`runLoopWithTools(tools: List[Tool], log: String => Unit, logging: LogConfig): Unit`, and have
`runLoop` call it after computing `tools`. This is a pure extraction — no behaviour change on the
resolved path.

Also move `stateFactory`/`activateState`/`stateCache.put` so they run only in the `None` branch;
the unresolved branch must not build an index over cwd.

- [ ] **Step 4: Stop exiting in `Main.scala`**

Replace lines 32–42 of `Main.scala` with:

```scala
    val (root, unresolved) =
      if rootArg != "." then (rootArg, None)
      else
        ProjectRootDiscovery.resolveDefaultRoot(
          Path.of("."),
          envOn("SCALASEMANTIC_SKIP_ROOT_CHECK")
        ) match
          case Right(resolved) => (resolved.toString, None)
          case Left(error) =>
            System.err.println(error)
            (".", Some(error))
    Mcp.serve(
      root,
      positional.drop(1).headOption,
      Mcp.LogConfig(enabled = logEnabled, logOutputs = logOutputs),
      unresolved
    )
```

The `System.err.println(error)` stays: stderr is where MCP clients surface server diagnostics, and
losing it would make the state harder to debug. Only `sys.exit(1)` goes.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mill mcp.test`
Expected: PASS.

- [ ] **Step 6: Verify by hand that a non-Scala directory now connects**

```bash
cd "$(mktemp -d)" && printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | java -jar "$OLDPWD/out/mcp/assembly.dest/out.jar" serve . ; echo "exit=$?"
```

Expected: both responses come back, `tools/list` is non-empty, `exit=0`. Before this task the
process exited 1 with no response at all.

- [ ] **Step 7: Commit**

```bash
git add mcp/src/main/scala/com/github/mercurievv/scalasemantic/Main.scala \
        mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/Mcp.scala \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/ProjectRootDiscoverySuite.scala \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/UnresolvedRootSuite.scala
git commit -m "fix(mcp): report an unresolved project root per tool call instead of exiting"
```

---

### Task 5: Client-drive and negative cases in the install test

**Files:**
- Modify: `scripts/test-install.sc`

**Interfaces:**
- Consumes: everything from Tasks 2–4.
- Produces: `scripts/test-install.sc` additionally proves that real MCP clients reach the server,
  and that a non-Scala directory connects instead of failing.

- [ ] **Step 1: Add the client-drive step**

Insert into `TestInstall`, before `main`:

```scala
  /** Headless invocations that force one MCP tool call. A client missing from PATH is skipped —
    * loudly — so an absent `agy` cannot mask a real failure in the `claude` path. */
  def clientCommands(prompt: String): Seq[(String, Seq[String])] = Seq(
    "claude" -> Seq("claude", "-p", prompt),
    "codex"  -> Seq("codex", "exec", prompt),
    "agy"    -> Seq("agy", "-p", prompt)
  )

  def onPath(exe: String): Boolean =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).exists { d =>
      Files.isExecutable(Paths.get(d).resolve(exe))
    }
```

and, in `main` between the "configs written" and "idempotent" blocks:

```scala
    // 5. Assert the server actually answers through a real client.
    val prompt =
      "Use the scala-semantic MCP tool find_symbol to look up the symbol " +
        "zzUniqueFixtureSymbol in this project, and print the tool's raw answer."
    var drove = 0
    clientCommands(prompt).foreach { case (name, cmd) =>
      if (!onPath(name)) println(s"[skip] $name not on PATH")
      else {
        val (c, o, e) = run(cmd, project, env)
        check(
          c == 0 && o.contains("zzUniqueFixtureSymbol"),
          s"$name did not get the fixture symbol back (exit $c)\n--- stdout ---\n$o\n--- stderr ---\n$e"
        )
        println(s"[ok] $name reached the server")
        drove += 1
      }
    }
    check(drove > 0, "no MCP client was available on PATH; this test proves nothing without one")
```

- [ ] **Step 2: Add the negative case**

Add to `TestInstall`, before `main`:

```scala
  /** ADR-0004: an unresolved root must still connect and must say why on each call. */
  def assertNonScalaDirConnects(launcher: Path, sandbox: Path, env: Map[String, String]): Unit = {
    val empty = Files.createDirectories(sandbox.resolve("not-a-scala-project"))
    val requests = Seq(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_symbol","arguments":{"name":"Anything"}}}"""
    ).mkString("", "\n", "\n")

    val pb = new ProcessBuilder(Seq(launcher.toString, "serve", ".").asJava)
    pb.directory(empty.toFile)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val proc = pb.start()
    proc.getOutputStream.write(requests.getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val code = proc.waitFor()

    check(code == 0, s"server exited $code in a non-Scala directory; it must stay connectable\n$out")
    check(out.contains("\"tools\""), s"tools/list returned nothing:\n$out")
    check(out.contains("find_symbol"), s"tool list is missing find_symbol:\n$out")
    check(
      out.contains("could not detect a Scala project root"),
      s"expected the discovery error from the tool call:\n$out"
    )
    println("[ok] non-Scala directory connects and explains itself")
  }
```

and call it in `main`, after the idempotency block and before teardown:

```scala
    assertNonScalaDirConnects(launcher, sandbox, env)
```

- [ ] **Step 3: Run the full test in both modes**

```
scala-cli scripts/test-install.sc -- user
scala-cli scripts/test-install.sc -- project
```

Expected: PASS, with at least one client driven (not all three skipped). If every client is skipped
the test fails by design — install one and re-run.

- [ ] **Step 4: Commit**

```bash
git add scripts/test-install.sc
git commit -m "test: drive real MCP clients and cover the non-Scala-directory case"
```

---

### Task 6: Documentation, ADR, and stale references

**Files:**
- Create: `docs/adr/0004-single-launcher-script-and-user-scope-install.md`
- Modify: `docs/getting-started/quickstart.md`
- Modify: `docs/getting-started/integration.md`
- Modify: `README.md`
- Modify: `scripts/smoke-test-scripts.sc` (only if it references a deleted file)

**Interfaces:**
- Consumes: the behaviour shipped in Tasks 2–5, including Task 2 Step 5's finding on which clients
  have a known user-level config.
- Produces: no code interface. This task closes the loop between what ships and what is documented.

- [ ] **Step 1: Write ADR-0004**

Create `docs/adr/0004-single-launcher-script-and-user-scope-install.md`, following the structure of
ADR-0003 (Context / Decision / Alternatives considered / Consequences). It must state:

- **Status:** accepted. **Date:** the day of implementation. **Applies to:**
  `scripts/scalasemantic-mcp.sh`, `LauncherSetup`, `LauncherClientConfigs`, `Main`, `Mcp`,
  `docs/getting-started/*`.
- **Context:** three implementations of install logic (`install.sh`, `scalasemantic-mcp.sh`,
  `scalasemantic-mcp.scala`), the last duplicating the `launcher/` module and already drifted — it
  emitted `scala-cli run --dependency ... -- .` argv where the module emits `serve .`. Per-project
  registration also costs a step in every repo forever, and does not actually onboard a teammate,
  because a checked-in config names a binary they do not have.
- **Decision 1:** one script; it self-installs when `$0` is unresolvable; all install decisions live
  in the jar.
- **Decision 2:** user scope is the default; `--project` selects project scope. User-scope configs
  carry an absolute command because GUI-launched clients frequently spawn without
  `~/.local/bin` on `PATH`; project-scope configs stay relative per ADR-0002.
- **Decision 3:** clients with no known user-level config location are skipped with a printed note,
  never given a guessed path. Name which of antigravity / cline / roo got a path in Task 2 Step 5
  and which were left as `None`, citing the documentation consulted.
- **Decision 4 (amends ADR-0003 §3):** discovery failure moves from `sys.exit(1)` to a per-tool-call
  error. ADR-0003's goal — never a confident `count: 0` from a wrong index — is preserved; only the
  reporting channel changes, because a process exit code is the wrong channel for a globally
  registered binary.
- **Consequences:** installing is one command; a user-scope registration is inert in non-Scala
  repositories; the jar cache moved from `${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp` to
  `$HOME/.local/share/scalasemantic-mcp` (installed data, not a cache — cache cleaners must not
  evict an 88 MB jar), so existing users re-download once; `docs/getting-started/*` and the README
  must be updated together with any future change to the one-liners.

Add a line at the top of ADR-0003 §3 pointing at ADR-0004 as its amendment, so a reader of 0003 does
not implement the superseded startup behaviour.

- [ ] **Step 2: Rewrite the quickstart**

`docs/getting-started/quickstart.md` step 1 becomes the two one-liners, with the user-scope form
first and labelled as the default:

```markdown
## Install

Install once for your user — every Scala project on this machine gets the server:

    curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh

Or install into a single project, so the launcher and config can be committed for your team:

    cd /path/to/your-project
    curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh -s -- --project

Prefer to read before running? Download it first — it is one short shell script:

    curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh -o scalasemantic-mcp.sh
    less scalasemantic-mcp.sh && sh scalasemantic-mcp.sh
```

Remove every remaining instruction to run `scripts/install.sh` or `scalasemantic-mcp setup` as a
second step. Project scope still runs SemanticDB setup, the rules file and the guard hook; user
scope registers the server only — say so, and say that a project install is what a Scala repo wants
when it has not been configured for SemanticDB yet.

- [ ] **Step 3: Update `integration.md`**

Rewrite the options section to describe the two scopes, the exact config file each client gets in
each scope (the table from Task 2 Step 4, including any `None`), and keep the existing
"Worktrees and cwd changes" guidance. State the new behaviour in a non-Scala directory: the server
connects and each tool call explains why it has no index, and `set_workspace_root` fixes it in band.

- [ ] **Step 4: Update the README quick setup**

Replace the `install.sh` one-liner with the user-scope one-liner and add the `--project` variant
beneath it. Keep the wording to two lines plus the commands.

- [ ] **Step 5: Verify no stale references remain**

Run: `grep -rn "install\.sh\|scalasemantic-mcp\.scala\|\.cache/scalasemantic-mcp" --include='*.md' --include='*.sh' --include='*.sc' --include='*.yml' --include='*.mill' . | grep -v '^./out/'`
Expected: no hits outside `docs/adr/`, where the historical mention in ADR-0003 and the new ADR-0004
are correct and intended.

- [ ] **Step 6: Run the full check suite**

Run: `./mill prePush`
Expected: PASS. `prePush` runs clean, format check, golden compatibility, all four modules' tests and
Stainless verification. `scripts/test-install.sc` is deliberately not part of it — run that by hand,
in both modes, before cutting a release.

- [ ] **Step 7: Commit**

```bash
git add docs/adr/0004-single-launcher-script-and-user-scope-install.md \
        docs/getting-started/quickstart.md docs/getting-started/integration.md README.md
git commit -m "docs: document the two install one-liners and record ADR-0004"
```

- [ ] **Step 8: Open the PR**

```bash
./tree2m --title "feat(install): one-liner install with user-scope registration" install-ux "feat(install): single self-installing launcher, user-scope registration"
```

The PR body must include, per this repo's convention: what was investigated, the key decisions
(single script; user scope by default; call-time root failure; guessed user paths refused), and the
rationale for each. Note explicitly that `scripts/test-install.sc` is local-only and was run in both
modes, with which clients were exercised and which were skipped.

---

## Self-Review

**Spec coverage.** Spec Decision 1 → Task 3. Decision 2 → Task 3 (script) + Task 2 (the `command`
value written per scope). Decision 3 → Task 2, with the three unverified paths resolved in Step 5.
Decision 4 → Task 4. Decision 5 → Tasks 1 and 5. Spec "Files touched" rows all appear:
`smoke-test-scripts.sc` is covered by Task 3 Step 5 and Task 6 Step 5, both of which fail the task if
a stale reference survives.

**Placeholder scan.** The only deferred decision is Task 2 Step 5, and it is deferred with a decision
procedure and a defined fallback (`None`, skip, record in the ADR) rather than left open. Every code
step carries the code.

**Type consistency.** `LauncherScope` (not `Scope`) is used in Tasks 2 and 3. `Options.scope` and
`Options.home` are introduced in Task 2 Step 3 and used in Task 2's tests and Step 4.
`targetPathFor(opts, client): Option[Path]` and `clientsWithoutUserScope: Seq[String]` are defined in
Task 2 Step 4 and referenced by Task 2 Step 1's tests. `Mcp.unresolvedRootTools(scratch, message)`
and `serve`'s fourth parameter `unresolvedRoot: Option[String]` are defined in Task 4 Step 3 and used
in Task 4 Steps 1 and 4. `runLoopWithTools(tools, log, logging)` is introduced as an extraction in
Task 4 Step 3 and used only there. The install test's `run`, `check`, `rmTree`, `fixtureProject`,
`expectedConfigs` are defined in Task 1 and reused unchanged in Task 5.
