# Local Install Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./mill installLocal` builds the fat jar and makes it the globally installed ScalaSemantic MCP server on this machine, until `scalasemantic-mcp --use-release` switches back.

**Architecture:** A `-local` suffix on the cached jar filename is the channel marker. The launcher (`scripts/scalasemantic-mcp.sh`) prefers such a jar and, while one exists, performs no release resolution and no background fetch. A new Mill task builds `mcp.assembly`, installs the launcher to `$BIN_DIR`, replaces the single `*-local.jar` in the data directory, and then delegates client configuration to the existing `install --scope user` path inside the jar — adding no second copy of install logic.

**Tech Stack:** POSIX `sh` (the launcher targets `sh`, not bash), Mill 1.1.7 `Task.Command` with `os-lib` and `mainargs`, scala-cli 3.8.4 scripts for the tests (house style, see `CLAUDE.md`).

**Spec:** `docs/superpowers/specs/2026-09-05-local-install-channel-design.md`

## Global Constraints

- The launcher is POSIX `sh` with `set -eu`. No bashisms (`[[`, arrays, `local`). Match the existing style in `scripts/scalasemantic-mcp.sh`.
- Data directory: `${SCALASEMANTIC_HOME:-$HOME/.local/share/scalasemantic-mcp}`. Launcher install directory: `${BIN_DIR:-$HOME/.local/bin}`. Both must be honoured everywhere; the tests depend on it.
- Local jar filename: `scalasemantic-mcp-<version>-local.jar`, one at a time. `<version>` comes from `publishVersion()` in `build.mill:583` (highest `v*` git tag, else `x.y.z`).
- Jar selection precedence in the launcher: `SCALASEMANTIC_JAR` env → `*-local.jar` → `SCALASEMANTIC_VERSION`/release → newest cached release. `SCALASEMANTIC_JAR` stays on top; the existing smoke tests set it.
- `newest_cached()` is `ls -t` — mtime order, not version order. Do not change it.
- The launcher must never invoke `mill`.
- Scripts under `scripts/` are scala-cli (`.sc`) per `CLAUDE.md`, run with `scala-cli run --server=false`.
- Every test redirects `BIN_DIR` and `SCALASEMANTIC_HOME` (and, where a client config could be written, `HOME`) into a temp directory. No test may touch the developer's real install.

**Deviation from the spec, deliberate:** the task takes a `--skip-clients` flag that stops after installing the launcher and jar, skipping step 4 (`install --scope user`). The spec describes only the full path. The flag exists because the end-to-end test cannot let a Mill-spawned child write MCP client configs into the developer's real `HOME`, and because re-installing a jar without rewriting configs is independently useful. Default behaviour is unchanged: without the flag, all four steps run.

---

### Task 1: Launcher prefers a `-local` jar and stops updating

**Files:**
- Modify: `scripts/scalasemantic-mcp.sh:81-96` (`jar_to_run`)
- Create: `scripts/smoke-tests-local-run/test-local-channel.sc`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `jar_to_run` returning a `*-local.jar` when the newest cached jar is one; the test file `test-local-channel.sc` with helpers `run`, `check`, `fail`, `stubBin`, `seedJar`, reused by Tasks 2 and 3.

- [ ] **Step 1: Write the failing test**

Create `scripts/smoke-tests-local-run/test-local-channel.sc`:

```scala
#!/usr/bin/env scala-cli

//> using scala 3.8.4

// Tests for the local jar channel (docs/superpowers/specs/2026-09-05-local-install-channel-design.md).
//
//   scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc
//   scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc -- with-mill
//
// Default run covers the launcher only: no network, no JDK toolchain download, no auth. It is
// hermetic (BIN_DIR / SCALASEMANTIC_HOME / HOME all point into a temp directory) and is wired into
// `./mill smokeTest`.
//
// `with-mill` additionally runs `./mill installLocal --skip-clients`. It is NOT wired into
// smokeTest, because smokeTest is itself a Mill task and must not re-enter Mill.

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object TestLocalChannel {

  val RepoRoot: Path = Paths.get(".").toAbsolutePath.normalize()
  val Launcher: Path = RepoRoot.resolve("scripts/scalasemantic-mcp.sh")

  def fail(msg: String): Nothing = {
    System.err.println(s"FAIL: $msg")
    sys.exit(1)
  }

  def check(cond: Boolean, msg: String): Unit = if (!cond) fail(msg)

  def rmTree(p: Path): Unit =
    if (Files.exists(p))
      Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(f => Files.delete(f))

  /** Runs a command, returning (exitCode, stdout, stderr). Never inherits stdin: the launcher
    * execs a server that would otherwise block reading it.
    */
  def run(cmd: Seq[String], cwd: Path, env: Map[String, String]): (Int, String, String) = {
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val proc = pb.start()
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  def write(p: Path, text: String, executable: Boolean = false): Path = {
    Files.createDirectories(p.getParent)
    Files.writeString(p, text)
    if (executable) p.toFile.setExecutable(true)
    p
  }

  /** A PATH directory whose `java` prints the jar it was handed instead of running it, and whose
    * `curl`/`wget` fail loudly. Any release download or background fetch therefore shows up as the
    * marker NETWORK-ATTEMPT in stderr, which the assertions below forbid.
    */
  def stubBin(dir: Path): Path = {
    write(dir.resolve("java"), "#!/bin/sh\necho \"JAVA-ARGS: $*\"\n", executable = true)
    write(
      dir.resolve("curl"),
      "#!/bin/sh\necho 'NETWORK-ATTEMPT curl' >&2\nexit 1\n",
      executable = true
    )
    write(
      dir.resolve("wget"),
      "#!/bin/sh\necho 'NETWORK-ATTEMPT wget' >&2\nexit 1\n",
      executable = true
    )
    dir
  }

  /** Places a fake cached jar with an explicit mtime, so selection order is deterministic rather
    * than dependent on how fast the test ran.
    */
  def seedJar(dataDir: Path, name: String, mtimeMillis: Long): Path = {
    val jar = write(dataDir.resolve(name), "not a real jar\n")
    Files.setLastModifiedTime(jar, attribute.FileTime.fromMillis(mtimeMillis))
    jar
  }

  def sandbox(): Path = Files.createTempDirectory("scalasemantic-local-channel")

  def testPrefersLocalJar(): Unit = {
    val box = sandbox()
    val home = Files.createDirectories(box.resolve("home"))
    val data = Files.createDirectories(box.resolve("data"))
    val bin = stubBin(Files.createDirectories(box.resolve("bin")))

    val now = System.currentTimeMillis()
    val local = seedJar(data, "scalasemantic-mcp-0.1.0-local.jar", now - 60000)
    // Newer by mtime AND higher by version: without the guard, either rule would pick it.
    seedJar(data, "scalasemantic-mcp-9.9.9.jar", now)

    val env = Map(
      "HOME" -> home.toString,
      "SCALASEMANTIC_HOME" -> data.toString,
      "PATH" -> s"$bin${java.io.File.pathSeparator}${sys.env.getOrElse("PATH", "")}"
    )
    val (code, out, err) = run(Seq(Launcher.toString, "serve", "."), box, env)

    check(code == 0, s"launcher exited $code\n--- out ---\n$out\n--- err ---\n$err")
    check(
      out.contains(local.toString),
      s"expected the -local jar ${local} to be selected, got:\n$out"
    )
    check(
      !out.contains("9.9.9"),
      s"a newer release jar outranked the -local jar:\n$out"
    )
    check(
      !err.contains("NETWORK-ATTEMPT"),
      s"launcher hit the network while a -local jar was installed:\n$err"
    )
    rmTree(box)
    println("[ok] -local jar wins and suppresses release resolution")
  }

  def main(args: Array[String]): Unit = {
    check(Files.exists(Launcher), s"launcher not found at $Launcher")
    testPrefersLocalJar()
    println("[ok] all local-channel tests passed")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc`

Expected: FAIL — `a newer release jar outranked the -local jar` (and/or a `NETWORK-ATTEMPT` failure, since the current `jar_to_run` background-fetches whenever a cached jar exists).

- [ ] **Step 3: Write minimal implementation**

In `scripts/scalasemantic-mcp.sh`, `jar_to_run` currently begins:

```sh
jar_to_run() {
  if [ -n "${SCALASEMANTIC_JAR:-}" ]; then printf '%s' "$SCALASEMANTIC_JAR"; return 0; fi
  cached=$(newest_cached)
```

Insert the guard directly after `cached=$(newest_cached)`:

```sh
jar_to_run() {
  if [ -n "${SCALASEMANTIC_JAR:-}" ]; then printf '%s' "$SCALASEMANTIC_JAR"; return 0; fi
  cached=$(newest_cached)
  # A locally built jar owns the machine while it is installed: no release resolution, no
  # background fetch, so an auto-update cannot silently revert the developer to a release.
  # `scalasemantic-mcp --use-release` removes it. See ADR-0005.
  case "$cached" in
    *-local.jar) printf '%s' "$cached"; return 0 ;;
  esac
```

Leave the rest of the function unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc`

Expected: PASS — `[ok] -local jar wins and suppresses release resolution`.

- [ ] **Step 5: Verify the existing smoke tests still select their jar**

Run: `./mill mcp.assembly && scala-cli run --server=false scripts/smoke-tests-local-run/smoke-test-scripts.sc`

Expected: PASS. This confirms `SCALASEMANTIC_JAR` still outranks everything, which the guard must not have disturbed.

- [ ] **Step 6: Commit**

```bash
git add scripts/scalasemantic-mcp.sh scripts/smoke-tests-local-run/test-local-channel.sc
git commit -m "feat(launcher): prefer a locally built -local jar and stop auto-updating"
```

---

### Task 2: `scalasemantic-mcp --use-release`

**Files:**
- Modify: `scripts/scalasemantic-mcp.sh:113-118` (the `--bg-fetch` / `--prefetch` case block)
- Modify: `scripts/smoke-tests-local-run/test-local-channel.sc`

**Interfaces:**
- Consumes: from Task 1, the `*-local.jar` preference in `jar_to_run`, and the test helpers `run`, `check`, `seedJar`, `stubBin`, `sandbox`, `rmTree`.
- Produces: a `--use-release` launcher flag that deletes every `*-local.jar` in `$DATA` and exits 0, idempotently.

- [ ] **Step 1: Write the failing test**

Add to `scripts/smoke-tests-local-run/test-local-channel.sc`, before `main`:

```scala
  def testUseReleaseRemovesLocalJar(): Unit = {
    val box = sandbox()
    val home = Files.createDirectories(box.resolve("home"))
    val data = Files.createDirectories(box.resolve("data"))
    val bin = stubBin(Files.createDirectories(box.resolve("bin")))

    val now = System.currentTimeMillis()
    val local = seedJar(data, "scalasemantic-mcp-0.1.0-local.jar", now - 60000)
    val release = seedJar(data, "scalasemantic-mcp-9.9.9.jar", now)

    val env = Map(
      "HOME" -> home.toString,
      "SCALASEMANTIC_HOME" -> data.toString,
      "PATH" -> s"$bin${java.io.File.pathSeparator}${sys.env.getOrElse("PATH", "")}"
    )

    val (code, _, err) = run(Seq(Launcher.toString, "--use-release"), box, env)
    check(code == 0, s"--use-release exited $code\n$err")
    check(!Files.exists(local), s"local jar still present after --use-release: $local")
    check(Files.exists(release), s"--use-release must not touch cached releases: $release")
    check(
      err.contains("scalasemantic-mcp-0.1.0-local.jar"),
      s"--use-release must name what it removed:\n$err"
    )

    // Idempotent: a second run is a clean no-op that says so.
    val (code2, _, err2) = run(Seq(Launcher.toString, "--use-release"), box, env)
    check(code2 == 0, s"second --use-release exited $code2\n$err2")
    check(
      err2.contains("no local jar"),
      s"second --use-release should report there is nothing to remove:\n$err2"
    )

    // Selection now falls back to the newest cached release.
    val (code3, out3, _) = run(Seq(Launcher.toString, "serve", "."), box, env)
    check(code3 == 0, s"launcher exited $code3 after --use-release")
    check(
      out3.contains(release.toString),
      s"expected fallback to the cached release $release, got:\n$out3"
    )

    rmTree(box)
    println("[ok] --use-release removes the local jar, idempotently, and falls back")
  }
```

And extend `main`:

```scala
  def main(args: Array[String]): Unit = {
    check(Files.exists(Launcher), s"launcher not found at $Launcher")
    testPrefersLocalJar()
    testUseReleaseRemovesLocalJar()
    println("[ok] all local-channel tests passed")
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc`

Expected: FAIL — `--use-release` is not a known flag, so the launcher falls through to `exec java -jar ... --use-release`; the stub `java` exits 0 without deleting anything and the assertion `local jar still present after --use-release` fires.

- [ ] **Step 3: Write minimal implementation**

In `scripts/scalasemantic-mcp.sh`, the case block after the bootstrap currently reads:

```sh
case "${1:-}" in
  --bg-fetch) background_fetch; exit 0 ;;
  --prefetch) shift; jar=$(jar_to_run)
              echo "scalasemantic-mcp: prefetched $(basename "$jar")" >&2; exit 0 ;;
esac
```

Add a third branch:

```sh
case "${1:-}" in
  --bg-fetch) background_fetch; exit 0 ;;
  --prefetch) shift; jar=$(jar_to_run)
              echo "scalasemantic-mcp: prefetched $(basename "$jar")" >&2; exit 0 ;;
  --use-release)
    removed=0
    for j in "$DATA"/*-local.jar; do
      [ -f "$j" ] || continue   # unmatched glob stays literal under sh; skip it
      rm -f "$j"
      echo "scalasemantic-mcp: removed $(basename "$j")" >&2
      removed=1
    done
    if [ "$removed" = 0 ]; then
      echo "scalasemantic-mcp: no local jar installed; already on the release channel" >&2
    else
      echo "scalasemantic-mcp: next start resolves a release" >&2
    fi
    exit 0 ;;
esac
```

- [ ] **Step 4: Run test to verify it passes**

Run: `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc`

Expected: PASS — both `[ok]` lines, then `[ok] all local-channel tests passed`.

- [ ] **Step 5: Wire the launcher tests into `smokeTest`**

In `build.mill`, `smokeTest()` already runs `scripts/smoke-tests-local-run/smoke-test-mill.sc` through `scala-cli run --server=false`. Add the same treatment for the new script, immediately after the existing scala-cli invocations:

```scala
  val localChannelScript =
    build.moduleDir / "scripts" / "smoke-tests-local-run" / "test-local-channel.sc"
  val localChannelRes = os
    .proc("scala-cli", "run", "--server=false", localChannelScript.toString)
    .call(cwd = build.moduleDir, stdout = os.Inherit, stderr = os.Inherit, check = false)
  if (localChannelRes.exitCode != 0) {
    sys.error(s"local channel test failed with exit status ${localChannelRes.exitCode}")
  }
```

Run: `./mill smokeTest`

Expected: PASS, including the two `[ok]` local-channel lines.

- [ ] **Step 6: Commit**

```bash
git add scripts/scalasemantic-mcp.sh scripts/smoke-tests-local-run/test-local-channel.sc build.mill
git commit -m "feat(launcher): add --use-release to leave the local jar channel"
```

---

### Task 3: `./mill installLocal`

**Files:**
- Modify: `build.mill` (add `installLocal` next to `smokeTest()`, around line 682)
- Modify: `scripts/smoke-tests-local-run/test-local-channel.sc`

**Interfaces:**
- Consumes: from Task 1, the `*-local.jar` preference; from Task 2, `--use-release`. From the existing build: `mcp.assembly()` (returns `PathRef`, `.path` is the fat jar) and `publishVersion()` (`build.mill:583`, a `T[String]`).
- Produces: `def installLocal(skipClients: mainargs.Flag) = Task.Command` — installs `${BIN_DIR:-$HOME/.local/bin}/scalasemantic-mcp` and exactly one `${SCALASEMANTIC_HOME:-$HOME/.local/share/scalasemantic-mcp}/scalasemantic-mcp-<version>-local.jar`, then unless `--skip-clients` runs `java -jar <jar> install --scope user`.

- [ ] **Step 1: Write the failing test**

Add to `scripts/smoke-tests-local-run/test-local-channel.sc`, before `main`:

```scala
  /** A Scala CLI fixture project with a distinctive symbol and a real SemanticDB, so the installed
    * launcher can be asked a question that only a working server answers.
    */
  def fixtureProject(parent: Path): Path = {
    val dir = Files.createDirectories(parent.resolve("fixture-project"))
    write(dir.resolve("project.scala"), "//> using scala 3.8.4\n")
    write(
      dir.resolve("Fixture.scala"),
      """|object Fixture:
         |  def zzLocalChannelSymbol(n: Int): Int = n + 1
         |""".stripMargin
    )
    val (code, out, err) = run(
      Seq(
        "scala-cli",
        "compile",
        "--semanticdb",
        "--semanticdb-sourceroot",
        ".",
        "--semanticdb-targetroot",
        "semanticdb",
        "."
      ),
      dir,
      Map.empty // ambient env on purpose: not re-downloading a toolchain into the sandbox
    )
    check(code == 0, s"fixture compile failed\n--- out ---\n$out\n--- err ---\n$err")
    dir
  }

  /** Drives the installed launcher over stdio with a real MCP handshake. */
  def assertServerAnswers(launcher: Path, project: Path, env: Map[String, String]): Unit = {
    val requests = Seq(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_symbol","arguments":{"name":"zzLocalChannelSymbol"}}}"""
    ).mkString("", "\n", "\n")

    val pb = new ProcessBuilder(Seq(launcher.toString, "serve", ".").asJava)
    pb.directory(project.toFile)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val proc = pb.start()
    proc.getOutputStream.write(requests.getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val code = proc.waitFor()

    check(code == 0, s"installed server exited $code\n$out")
    check(out.contains("find_symbol"), s"tools/list is missing find_symbol:\n$out")
    check(out.contains("zzLocalChannelSymbol"), s"server did not resolve the fixture symbol:\n$out")
  }

  def localJars(dataDir: Path): List[Path] = {
    val s = Files.list(dataDir)
    try s.iterator().asScala.filter(_.getFileName.toString.endsWith("-local.jar")).toList
    finally s.close()
  }

  def testInstallLocal(): Unit = {
    val box = sandbox()
    val home = Files.createDirectories(box.resolve("home"))
    val data = Files.createDirectories(box.resolve("data"))
    val bin = Files.createDirectories(box.resolve("bin")) // real java here, not the stub
    val project = fixtureProject(box)

    // A stale release jar that the install must end up outranked by.
    seedJar(data, "scalasemantic-mcp-9.9.9.jar", System.currentTimeMillis())

    val millEnv = Map("BIN_DIR" -> bin.toString, "SCALASEMANTIC_HOME" -> data.toString)
    val (code, out, err) =
      run(Seq("./mill", "installLocal", "--skip-clients"), RepoRoot, millEnv)
    check(code == 0, s"installLocal exited $code\n--- out ---\n$out\n--- err ---\n$err")

    val launcher = bin.resolve("scalasemantic-mcp")
    check(Files.exists(launcher), s"launcher missing after installLocal: $launcher")
    check(Files.isExecutable(launcher), s"launcher not executable: $launcher")
    check(localJars(data).size == 1, s"expected exactly one -local jar, got ${localJars(data)}")

    val serverEnv = Map("HOME" -> home.toString, "SCALASEMANTIC_HOME" -> data.toString)
    assertServerAnswers(launcher, project, serverEnv)
    println("[ok] installLocal installs a working launcher and one local jar")

    // Idempotent: re-running replaces rather than accumulates, and still answers.
    val (code2, out2, err2) =
      run(Seq("./mill", "installLocal", "--skip-clients"), RepoRoot, millEnv)
    check(code2 == 0, s"second installLocal exited $code2\n--- out ---\n$out2\n--- err ---\n$err2")
    check(localJars(data).size == 1, s"second run left ${localJars(data).size} local jars")
    assertServerAnswers(launcher, project, serverEnv)

    rmTree(box)
    println("[ok] installLocal is idempotent")
  }
```

And extend `main` to run it only when asked:

```scala
  def main(args: Array[String]): Unit = {
    check(Files.exists(Launcher), s"launcher not found at $Launcher")
    testPrefersLocalJar()
    testUseReleaseRemovesLocalJar()
    if (args.filterNot(_ == "--").contains("with-mill")) testInstallLocal()
    println("[ok] all local-channel tests passed")
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc -- with-mill`

Expected: FAIL — `installLocal exited 1`, with Mill reporting an unknown task `installLocal`.

- [ ] **Step 3: Write minimal implementation**

In `build.mill`, immediately before `def smokeTest() = Task.Command {`, add:

```scala
// Local development channel: build the fat jar and make it THE installed server on this machine.
// The `-local` suffix is the channel marker the launcher keys on — while such a jar exists it does
// no release resolution and no background fetch, so an auto-update cannot revert the developer to
// a release. `scalasemantic-mcp --use-release` removes it. See ADR-0005.
//
//   ./mill installLocal                  # jar + launcher + MCP client configs
//   ./mill installLocal --skip-clients   # jar + launcher only, leave client configs alone
def installLocal(skipClients: mainargs.Flag) = Task.Command {
  val jar = mcp.assembly().path
  val version = publishVersion()

  val home = os.Path(sys.env.getOrElse("HOME", sys.error("HOME is not set")))
  val binDir = sys.env.get("BIN_DIR").map(os.Path(_, os.pwd)).getOrElse(home / ".local" / "bin")
  val dataDir = sys.env
    .get("SCALASEMANTIC_HOME")
    .map(os.Path(_, os.pwd))
    .getOrElse(home / ".local" / "share" / "scalasemantic-mcp")

  os.makeDir.all(binDir)
  os.makeDir.all(dataDir)

  // Same destination the launcher's own self_install uses, so a machine installed via the curl
  // one-liner is upgraded in place instead of gaining a second binary.
  val launcher = binDir / "scalasemantic-mcp"
  os.copy.over(build.moduleDir / "scripts" / "scalasemantic-mcp.sh", launcher)
  os.perms.set(launcher, "rwxr-xr-x")

  // Exactly one local jar at a time: "which local build is active" must have one answer.
  os.list(dataDir).filter(_.last.endsWith("-local.jar")).foreach(os.remove)
  val localJar = dataDir / s"scalasemantic-mcp-$version-local.jar"
  os.copy.over(jar, localJar)

  if (!skipClients.value) {
    // Client configs, SemanticDB setup and the guard hook stay in the launcher/ module inside the
    // jar. This task adds no install logic of its own.
    val res = os
      .proc("java", "-jar", localJar.toString, "install", "--scope", "user")
      .call(
        cwd = build.moduleDir,
        env = Map(
          "SCALASEMANTIC_JAR" -> localJar.toString,
          "SCALASEMANTIC_LAUNCHER" -> launcher.toString
        ),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = false
      )
    if (res.exitCode != 0) {
      sys.error(
        s"`install --scope user` failed with exit status ${res.exitCode}. The launcher " +
          s"($launcher) and jar ($localJar) ARE installed; only MCP client configuration " +
          "failed. Re-running `./mill installLocal` is safe."
      )
    }
  }

  println(s"installed launcher: $launcher")
  println(s"installed jar:      $localJar")
  println("restart your MCP clients to pick it up; `scalasemantic-mcp --use-release` reverts")
}
```

If `mainargs` is not already imported in `build.mill`, write the parameter type as `mainargs.Flag` exactly as above — it resolves through Mill's own `mainargs` dependency without an import.

- [ ] **Step 4: Run test to verify it passes**

Run: `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc -- with-mill`

Expected: PASS — `[ok] installLocal installs a working launcher and one local jar` and `[ok] installLocal is idempotent`.

- [ ] **Step 5: Verify the default (non-test) run has not been left broken**

Run: `./mill installLocal --skip-clients` (no env overrides), then `ls ~/.local/share/scalasemantic-mcp` and `~/.local/bin/scalasemantic-mcp --use-release`.

Expected: exactly one `*-local.jar` before `--use-release`, none after, and the cached release jars untouched. This exercises the real paths once, then leaves the machine on whatever channel it was on.

- [ ] **Step 6: Commit**

```bash
git add build.mill scripts/smoke-tests-local-run/test-local-channel.sc
git commit -m "feat(build): add ./mill installLocal for a globally installed local build"
```

---

### Task 4: Documentation and ADR-0005

**Files:**
- Create: `docs/adr/0005-local-jar-channel.md`
- Modify: `docs/getting-started/integration.md`

**Interfaces:**
- Consumes: the finished behaviour of Tasks 1-3 — `./mill installLocal`, `--skip-clients`, `--use-release`, the `*-local.jar` marker.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write ADR-0005**

Create `docs/adr/0005-local-jar-channel.md`, matching the house format of `docs/adr/0004-single-launcher-script-and-user-scope-install.md` (read it first for heading structure and tone):

```markdown
# 5. Local jar channel

Date: 2026-09-05

## Status

Accepted.

## Context

Every install path ends at a GitHub release: the launcher resolves the newest tag, downloads the
jar into the user data directory, and background-fetches newer releases on later starts. A change
to `core/`, `analysis/` or `mcp/` therefore reaches the developer's own MCP clients only after a
tag, a CI publish and a fetch. The `SCALASEMANTIC_JAR` override exists but lives in one client
config for one project — it is neither global nor durable.

## Decision

A cached jar whose filename ends in `-local.jar` marks the local development channel.

1. `./mill installLocal` builds `mcp.assembly`, installs the launcher to `${BIN_DIR:-~/.local/bin}`,
   replaces the single `*-local.jar` in `${SCALASEMANTIC_HOME:-~/.local/share/scalasemantic-mcp}`,
   and then runs `java -jar <jar> install --scope user` so client configuration keeps going through
   the `launcher/` module rather than a second implementation. `--skip-clients` stops after the jar
   and launcher.
2. While a `*-local.jar` exists, the launcher selects it and performs no release resolution and no
   background fetch. Selection is by mtime (`ls -t`); the version in the filename is a label for
   humans.
3. `scalasemantic-mcp --use-release` deletes every `*-local.jar` and exits, returning the machine to
   the release channel. It is idempotent.

Exactly one channel is active at a time, and the precedence is total:
`SCALASEMANTIC_JAR` → `*-local.jar` → `SCALASEMANTIC_VERSION`/release → newest cached release.

## Consequences

- Edit, `./mill installLocal`, restart clients: every project on the machine runs the new build.
- The local channel does not expire on its own. That is the point — an auto-update must not
  silently revert a developer mid-debug — but it means a forgotten local jar keeps a machine off
  releases indefinitely. `--use-release` is the documented exit, and `installLocal` prints it.
- The launcher never invokes a build tool, so server startup stays `java -jar`.

## Alternatives considered

**Coursier.** `./mill mcp.publishLocal` into `~/.ivy2/local` plus `cs launch -r ivy2Local` resolves
transitive dependencies correctly and would work. Rejected for two reasons. Merging a coursier
channel with the home-directory channel has no total order: an Ivy/Maven semver and a file mtime are
not comparable, so `0.4.2` from Central versus a thirty-second-old local build of `0.4.2` requires an
invented rule, which becomes a rule to debug when the wrong build answers a tool call. And
resolution would land on every server start — an MCP server starts per client launch per project,
where `java -jar` needs neither network nor resolution. Coursier remains reasonable as a future
*alternative release* channel (`cs install` in place of the curl one-liner); it would replace the
release channel, never merge with the local one.

**Auto-rebuild on server start.** Rejected: it puts a Mill invocation on the startup path of every
client launch and makes a JDK-plus-build-tool a runtime requirement of the server.
```

- [ ] **Step 2: Document the workflow in the getting-started guide**

Read `docs/getting-started/integration.md` first to match its heading level and voice, then add a section (after the install sections, before any troubleshooting section):

```markdown
## Developing on ScalaSemantic

To run your own build of the server in every project on your machine:

```bash
./mill installLocal
```

This builds the fat jar, installs the launcher to `~/.local/bin/scalasemantic-mcp`, places the jar
as the single `*-local.jar` in `~/.local/share/scalasemantic-mcp/`, and configures your MCP clients
through the same code path the release install uses. Restart your MCP clients to pick it up.

While a local jar is installed the launcher never resolves or downloads a release, so an
auto-update cannot revert you mid-change. Go back to releases with:

```bash
scalasemantic-mcp --use-release
```

`./mill installLocal --skip-clients` installs the jar and launcher without touching client configs —
useful once your configs are already written. `BIN_DIR` and `SCALASEMANTIC_HOME` override both
destinations. See [ADR-0005](../adr/0005-local-jar-channel.md).
```

- [ ] **Step 3: Verify the docs build**

Run: `./mill smokeTest`

Expected: PASS. (If the docs site has its own link check task, run that too; a broken relative link to `../adr/0005-local-jar-channel.md` is the likely failure.)

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0005-local-jar-channel.md docs/getting-started/integration.md
git commit -m "docs: document the local jar channel and record ADR-0005"
```

---

## Final verification

- [ ] `./mill prePush` passes (clean, format, golden, all four module test suites, stainless).
- [ ] `./mill smokeTest` passes, including the two local-channel `[ok]` lines.
- [ ] `scala-cli run --server=false scripts/smoke-tests-local-run/test-local-channel.sc -- with-mill` passes.
- [ ] `git status` is clean and no `*-local.jar` or launcher copy is tracked.
