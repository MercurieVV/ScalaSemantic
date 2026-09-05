#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// End-to-end install test for the ScalaSemantic MCP one-liners. LOCAL ONLY — it drives real
// `claude`/`codex`/`agy` binaries, which need authentication, so this is never wired into CI.
// Run from the repo root:
//
//   ./mill mcp.assembly
//   scala-cli scripts/test-install.sc -- user
//   scala-cli scripts/test-install.sc -- project
//
// Each run is hermetic: a fresh temp directory is used as HOME, so nothing touches the developer's
// real configs, and the run starts by clearing every path it will later assert on.
//
// Two env overrides keep the test on local code rather than on the last published release, which by
// definition does not contain the change under test:
//   SCALASEMANTIC_JAR      — use the locally built assembly instead of downloading a release jar
//   SCALASEMANTIC_SELF_SRC — self-install by copying this repo's script instead of curling it

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object TestInstall {

  val RepoRoot: Path = Paths.get(".").toAbsolutePath.normalize()
  val Installer: Path = RepoRoot.resolve("scripts/scalasemantic-mcp.sh")
  val LocalJar: Path = RepoRoot.resolve("out/mcp/assembly.dest/out.jar")

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
    proc.getOutputStream.close() // no stdin: otherwise CLI clients stall waiting for piped input
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  /** A real Scala CLI project carrying `project.scala` (a root-discovery marker) and one
    * distinctively-named symbol, compiled so an actual SemanticDB exists — without it every
    * `find_symbol` below would answer "not found" no matter how correct the install was.
    *
    * Compiled with the ambient environment on purpose: the toolchain download is not what this
    * test exercises, and forcing it through the sandbox HOME would refetch all of Scala.
    */
  def fixtureProject(parent: Path): Path = {
    val dir = parent.resolve("fixture-project")
    Files.createDirectories(dir)
    Files.writeString(
      dir.resolve("project.scala"),
      // SemanticDB comes from the --semanticdb CLI flag below, not a directive: `//> using
      // semanticdb` is not recognised by Scala CLI.
      "//> using scala 3.8.4\n"
    )
    Files.writeString(
      dir.resolve("Fixture.scala"),
      """|object Fixture:
         |  def zzUniqueFixtureSymbol(n: Int): Int = n + 1
         |""".stripMargin
    )
    // The target root must be a VISIBLE directory: Scala CLI's default puts SemanticDB under the
    // hidden .scala-build/, and SemanticIndex skips hidden directories while walking a project.
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
      Map.empty
    )
    check(code == 0, s"fixture failed to compile\n--- stdout ---\n$out\n--- stderr ---\n$err")
    val semanticdbs =
      Files.walk(dir).iterator().asScala.filter(_.toString.endsWith(".semanticdb")).toVector
    check(semanticdbs.nonEmpty, s"fixture compiled but emitted no SemanticDB under $dir")
    dir
  }

  /** Speaks raw JSON-RPC to the installed launcher and asserts the fixture symbol comes back. This
    * is what proves the install works in both scopes; the LLM clients below are the extra mile.
    */
  def assertServerAnswers(launcher: Path, project: Path, env: Map[String, String]): Unit = {
    val requests = Seq(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_symbol","arguments":{"name":"zzUniqueFixtureSymbol"}}}"""
    ).mkString("", "\n", "\n")
    val pb = new ProcessBuilder(Seq(launcher.toString, "serve", ".").asJava)
    pb.directory(project.toFile)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val proc = pb.start()
    proc.getOutputStream.write(requests.getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    check(proc.waitFor() == 0, s"server exited non-zero\n$out\n$err")
    check(
      out.contains("zzUniqueFixtureSymbol"),
      s"server did not resolve the fixture symbol\n--- stdout ---\n$out\n--- stderr ---\n$err"
    )
    println("[ok] server resolves the fixture symbol")
  }

  /** Config files each mode is expected to create, relative to HOME (user) or project (project). */
  def expectedConfigs(mode: String): Seq[String] =
    // cline and roo are absent on purpose: their global config lives inside VS Code's OS-specific
    // globalStorage, so they have no user-scope path and the installer skips them.
    if (mode == "user")
      Seq(
        ".claude.json",
        ".codex/config.toml",
        ".gemini/settings.json",
        ".gemini/config/mcp_config.json",
        ".continue/config.yaml"
      )
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

  /** Headless invocations that force one MCP tool call. A client missing from PATH is skipped —
    * loudly — so an absent `agy` cannot mask a real failure in the `claude` path.
    */
  def clientCommands(prompt: String, dshOverlay: Path): Seq[(String, Seq[String])] = Seq(
    // Each client needs its tool call pre-approved: headless runs cannot answer a prompt.
    // --allowedTools takes a list, so use the =form: otherwise it swallows the prompt argument.
    "claude" -> Seq("claude", "--allowedTools=mcp__scala-semantic__find_symbol", "-p", prompt),
    "dsh" -> Seq(
      "dsh",
      "--profile",
      "headless",
      "--patch",
      dshOverlay.toAbsolutePath.toString,
      prompt
    ),
    // agy: disabled until antigravity-cli#548 is fixed — headless mode ignores permissions.allow
    // entirely, so the only way to drive it is --dangerously-skip-permissions. Re-enable with:
    //
    //   "agy" -> Seq("agy", "-p", prompt)      // plus seedAgyPermissions(project)
    //
    // codex: temporarily disabled (no API credits available to run it).
    //
    // Re-enabling is not just uncommenting. `codex --help` documents config as loaded from
    // ~/.codex/config.toml, overridable only via $CODEX_HOME; there is no project-local
    // .codex/config.toml discovery. A run here duly ignored the fixture's .codex/config.toml and
    // reached for the developer's global MCP servers instead. So project-scope Codex support is a
    // no-op today, and driving Codex hermetically needs CODEX_HOME pointed at a directory holding
    // both the generated config and a copy of the real auth.json:
    //
    //   "codex" -> Seq("codex", "exec", "--skip-git-repo-check", prompt)
  )

  /** Grants exactly the tools the client check needs, in the project-level settings the installer
    * already writes — the alternative to auto-approving everything with
    * --dangerously-skip-permissions.
    *
    * UNUSED for now: agy's headless (`-p`) mode never consults `permissions.allow` in any scope,
    * so no rule set can work there yet — https://github.com/google-antigravity/antigravity-cli/issues/548.
    * Kept ready so agy can be re-enabled in `clientCommands` the moment that lands.
    */
  def seedAgyPermissions(project: Path): Unit = {
    val file = project.resolve(".gemini/settings.json")
    Files.createDirectories(file.getParent)
    val json =
      if (Files.exists(file)) ujson.read(Files.readString(file)) else ujson.Obj()
    json("permissions") = ujson.Obj(
      // Every rule takes a target — a bare tool name is silently ignored.
      "allow" -> ujson.Arr(
        "read_file(*)",
        "read_many_files(*)",
        "glob(*)",
        "search_file_content(*)",
        // Spawning the stdio MCP server itself counts as a "command".
        "command(*)",
        "run_shell_command(*)",
        "find_symbol(*)",
        "mcp__scala-semantic__find_symbol(*)",
        "scala-semantic__find_symbol(*)"
      )
    )
    Files.writeString(file, ujson.write(json, indent = 2))
  }

  /** dsh (DeepSeek Harness) configures MCP through a cordis patch overlay, one server per plugin
    * entry — not through any `mcpServers` map, so the installer writes nothing for it. Its
    * `headless` profile also omits the MCP plugin entirely, so overlay it here. This proves the
    * launcher works under dsh; it does NOT prove the installer configured dsh, because it cannot.
    */
  def dshPatch(project: Path, launcher: Path): Path = {
    val patch = project.resolve("dsh-scalasemantic.patch.yml")
    Files.writeString(
      patch,
      s"""|- name: '@deepseek-ai/dsh-mcp-client'
          |  config:
          |    serverName: scalasemantic
          |    transport: stdio
          |    command: '${launcher.toAbsolutePath}'
          |    args:
          |      - serve
          |      - .
          |    cwd: '${project.toAbsolutePath}'
          |""".stripMargin
    )
    patch
  }

  def onPath(exe: String): Boolean =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .exists(d => Files.isExecutable(Paths.get(d).resolve(exe)))

  /** ADR-0004: an unresolved root must still connect and must say why on each call. */
  def assertNonScalaDirConnects(launcher: Path, sandbox: Path, env: Map[String, String]): Unit = {
    val empty = Files.createDirectories(sandbox.resolve("not-a-scala-project"))
    val requests = Seq(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
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

  def main(args: Array[String]): Unit = {
    // scala-cli forwards the `--` separator itself; drop it so both invocation styles work.
    val mode = args.filterNot(_ == "--").headOption
      .getOrElse(fail("usage: test-install.sc -- user|project"))
    check(mode == "user" || mode == "project", s"unknown mode '$mode'")
    check(Files.exists(Installer), s"installer not found at $Installer")
    check(Files.exists(LocalJar), s"build the jar first: ./mill mcp.assembly (expected $LocalJar)")

    val sandbox = Files.createTempDirectory("scalasemantic-install-test")
    val home = sandbox.resolve("home")
    Files.createDirectories(home)
    val project = fixtureProject(sandbox)
    val env = Map(
      "HOME" -> home.toString,
      "SCALASEMANTIC_TEST" -> "1",
      "SCALASEMANTIC_JAR" -> LocalJar.toString,
      "SCALASEMANTIC_SELF_SRC" -> Installer.toString
    )

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
    val modeFlag = if (mode == "user") "" else " -s -- --project"
    val installCmd = Seq("sh", "-c", s"cat ${Installer.toString} | sh$modeFlag")
    val (code, out, err) = run(installCmd, project, env)
    check(code == 0, s"install exited $code\n--- stdout ---\n$out\n--- stderr ---\n$err")
    println("[ok] installed")

    // 4. Assert installed.
    check(Files.exists(launcher), s"launcher missing after install: $launcher")
    check(Files.isExecutable(launcher), s"launcher not executable: $launcher")

    val expectedCommand = if (mode == "user") launcher.toString else "./scalasemantic-mcp.sh"
    configs.foreach { c =>
      check(Files.exists(c), s"config missing after install: $c")
      val text = Files.readString(c)
      check(text.contains("scala-semantic"), s"server name missing in $c:\n$text")
      check(
        text.contains(expectedCommand),
        s"expected command '$expectedCommand' missing in $c:\n$text"
      )
      check(text.contains("serve"), s"'serve' arg missing in $c:\n$text")
    }
    println("[ok] configs written")

    // 5. Assert the installed server actually answers, over the wire.
    assertServerAnswers(launcher, project, env)

    // 5b. In project mode the config is project-local, so the real HOME can be left alone and the
    //     LLM clients stay authenticated — drive them for a true end-to-end check. User mode cannot
    //     do this: its config lives in HOME, and pointing a client at the real HOME would mean
    //     writing the test's server entry into the developer's own configs.
    if (mode == "project") {
      val prompt =
        "Use the scala-semantic MCP tool find_symbol to look up the symbol " +
          "zzUniqueFixtureSymbol in this project, and print the tool's raw answer."
      val clientEnv = env - "HOME"
      var drove = 0
      clientCommands(prompt, dshPatch(project, launcher)).foreach { case (name, cmd) =>
        if (!onPath(name)) println(s"[skip] $name not on PATH")
        else {
          val (c, o, e) = run(cmd, project, clientEnv)
          check(
            c == 0 && o.contains("zzUniqueFixtureSymbol"),
            s"$name did not get the fixture symbol back (exit $c)\n--- stdout ---\n$o\n--- stderr ---\n$e"
          )
          println(s"[ok] $name reached the server")
          drove += 1
        }
      }
      check(drove > 0, "no MCP client was available on PATH; install one to run this step")
    } else println("[skip] LLM clients: user scope would need the developer's real HOME")

    // 6. Idempotent: a second install must not change a byte, must not duplicate the entry, and
    //    must preserve an unrelated server planted beforehand.
    val claudeConfig = configs.head
    val before = Files.readString(claudeConfig)
    val (code2, out2, err2) = run(installCmd, project, env)
    check(code2 == 0, s"second install exited $code2\n$out2\n$err2")
    val after = Files.readString(claudeConfig)
    check(
      before == after,
      s"install is not idempotent:\n--- before ---\n$before\n--- after ---\n$after"
    )
    check(
      after.sliding("scala-semantic".length).count(_ == "scala-semantic") == 1,
      s"duplicate scala-semantic entry after re-install:\n$after"
    )
    println("[ok] idempotent")

    assertNonScalaDirConnects(launcher, sandbox, env)

    // 7. Teardown.
    rmTree(sandbox)
    check(!Files.exists(sandbox), s"sandbox not removed: $sandbox")
    println(s"[PASS] install test ($mode)")
  }
}
