#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// Local end-to-end test for annotation-aware EDITING: the guard hook's edit-time steer and the
// isomorphic read/write roundtrip it points at. LOCAL ONLY — it installs through the real
// installer script and speaks JSON-RPC to a real server, so it is not wired into CI.
//
//   ./mill mcp.assembly
//   scala-cli scripts/smoke-tests-local-run/test-edit-guard.sc
//
// Hermetic: a fresh temp directory is used as HOME and as the project, so nothing touches the
// developer's configs. Env overrides keep the run on locally built code:
//   SCALASEMANTIC_JAR      — use the locally built assembly instead of downloading a release jar
//   SCALASEMANTIC_SELF_SRC — self-install by copying this repo's script instead of curling it

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object TestEditGuard {

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

  def run(cmd: Seq[String], cwd: Path, env: Map[String, String]): (Int, String, String) = {
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    val e = pb.environment()
    env.foreach { case (k, v) => e.put(k, v) }
    val proc = pb.start()
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  /** A real compiled Scala CLI project: the hook fails open without an emitted SemanticDB, and the
    * roundtrip below needs real annotations to inject.
    */
  def fixtureProject(parent: Path): Path = {
    val dir = parent.resolve("fixture-project")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("project.scala"), "//> using scala 3.8.4\n")
    Files.writeString(
      dir.resolve("Fixture.scala"),
      """|object Fixture:
         |  def sizes(xs: List[String]) = xs.map(_.length)
         |""".stripMargin
    )
    // Visible target root: SemanticIndex skips hidden directories, and Scala CLI defaults to
    // .scala-build/.
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
    dir
  }

  // --- the guard hook, executed for real -----------------------------------------------------

  /** Feeds one PreToolUse payload to the installed hook. Returns (exit, stdout, stderr). */
  def hook(project: Path, payload: String): (Int, String, String) = {
    val script = project.resolve(".claude/hooks/scala-semantic-guard.sh")
    val pb = new ProcessBuilder(Seq("sh", script.toString).asJava)
    pb.directory(project.toFile)
    pb.environment().put("CLAUDE_PROJECT_DIR", project.toString)
    val proc = pb.start()
    proc.getOutputStream.write(payload.getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  def edit(file: String): String =
    s"""{"tool_name":"Edit","tool_input":{"file_path":"$file"}}"""

  def assertEditReminder(project: Path): Unit = {
    val (code, out, err) = hook(project, edit("Fixture.scala"))
    check(code == 0, s"editing a .scala file must NOT be blocked by default (exit $code)\n$err")
    check(
      out.contains("annotated_source") && out.contains("sentinel"),
      s"expected the annotation-aware-edit reminder on stdout, got:\n$out"
    )
    check(
      out.contains("compilable"),
      s"the reminder must name format=compilable — sentinel alone keeps the gutter:\n$out"
    )

    val (wCode, wOut, _) = hook(project, """{"tool_name":"Write","tool_input":{"file_path":"a.sc"}}""")
    check(wCode == 0, s"Write of a .sc file must not be blocked by default (exit $wCode)")
    check(wOut.contains("annotated_source"), s"expected the reminder for .sc too:\n$wOut")

    val shell =
      """{"tool_name":"Bash","tool_input":{"command":"cat > Fixture.scala <<EOF\nx\nEOF"}}"""
    val (bCode, bOut, _) = hook(project, shell)
    check(bCode == 0, s"shell write must not be blocked by default (exit $bCode)")
    check(bOut.contains("annotated_source"), s"expected the reminder for a shell write:\n$bOut")

    println("[ok] edit-time reminder fires on .scala/.sc writes")
  }

  def assertQuietElsewhere(project: Path): Unit = {
    Seq(
      edit("README.md"),
      edit("build.mill"),
      edit("project.scala.json"),
      """{"tool_name":"Write","tool_input":{"file_path":".claude/settings.json"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"./mill __.compile"}}"""
    ).foreach { payload =>
      val (code, out, _) = hook(project, payload)
      check(code == 0, s"must stay out of the way: $payload (exit $code)")
      check(out.trim.isEmpty, s"must say nothing for $payload, got:\n$out")
    }
    println("[ok] non-Scala targets pass silently")
  }

  def assertReadsStillDenied(project: Path): Unit = {
    val (code, _, err) = hook(project, """{"tool_name":"Read","tool_input":{"file_path":"Fixture.scala"}}""")
    check(code == 2, s"reading a .scala file must still be denied (exit $code)")
    check(err.contains("BLOCKED"), s"expected the deny message on stderr:\n$err")
    println("[ok] read-side deny unchanged")
  }

  def assertStrictDenies(project: Path, launcher: Path, env: Map[String, String]): Unit = {
    val (code, out, err) =
      run(Seq(launcher.toString, "setup", "--project", ".", "--client", "claude", "--strict-edits"), project, env)
    check(code == 0, s"--strict-edits setup exited $code\n$out\n$err")

    val (eCode, _, eErr) = hook(project, edit("Fixture.scala"))
    check(eCode == 2, s"under --strict-edits an Edit of a .scala file must be denied (exit $eCode)")
    check(
      eErr.contains("annotated_source"),
      s"the strict deny must name the write path to use instead:\n$eErr"
    )

    val (mCode, mOut, _) = hook(project, edit("README.md"))
    check(mCode == 0 && mOut.trim.isEmpty, "strict mode must still ignore non-Scala files")

    val settings = Files.readString(project.resolve(".claude/settings.json"))
    check(
      "scala-semantic-guard".r.findAllIn(settings).size == 1,
      s"re-running setup duplicated the guard registration:\n$settings"
    )
    println("[ok] --strict-edits denies, idempotently")
  }

  /** A project set up before this change carries the old matcher. Re-running setup must widen it,
    * or the whole edit branch is dead code on every existing install.
    */
  def assertMatcherUpgrade(project: Path, launcher: Path, env: Map[String, String]): Unit = {
    val settings = project.resolve(".claude/settings.json")
    Files.writeString(
      settings,
      """|{
         |  "hooks": {
         |    "PreToolUse": [
         |      {
         |        "matcher": "Read|Grep|Glob|Bash",
         |        "hooks": [
         |          { "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/scala-semantic-guard.sh" }
         |        ]
         |      }
         |    ]
         |  }
         |}
         |""".stripMargin
    )
    val (code, out, err) =
      run(Seq(launcher.toString, "setup", "--project", ".", "--client", "claude"), project, env)
    check(code == 0, s"setup over an old install exited $code\n$out\n$err")

    val text = Files.readString(settings)
    check(text.contains("Edit"), s"the old matcher was not widened to cover edits:\n$text")
    check(
      "scala-semantic-guard".r.findAllIn(text).size == 1,
      s"upgrading the matcher duplicated the entry:\n$text"
    )
    println("[ok] an existing install's matcher is upgraded in place")
  }

  // --- the isomorphic roundtrip the reminder points at ----------------------------------------

  /** Speaks JSON-RPC to the server, returning the parsed result of each tools/call. */
  def rpc(launcher: Path, project: Path, env: Map[String, String], calls: Seq[ujson.Value]): Seq[ujson.Value] = {
    val init =
      """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""
    val lines = init +: calls.zipWithIndex.map { case (c, i) =>
      ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> (i + 1), "method" -> "tools/call", "params" -> c))
    }
    val pb = new ProcessBuilder(Seq(launcher.toString, "serve", ".").asJava)
    pb.directory(project.toFile)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val proc = pb.start()
    proc.getOutputStream.write(lines.mkString("", "\n", "\n").getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    check(proc.waitFor() == 0, s"server exited non-zero\n$out\n$err")
    out.linesIterator.filter(_.trim.nonEmpty).map(ujson.read(_)).toSeq
  }

  def call(name: String, args: (String, ujson.Value)*): ujson.Value =
    ujson.Obj("name" -> name, "arguments" -> ujson.Obj.from(args))

  /** The MCP result payload: content[0].text carries the tool's JSON. */
  def payload(msg: ujson.Value): ujson.Value =
    ujson.read(msg("result")("content")(0)("text").str)

  def assertInstructionsSteer(launcher: Path, project: Path, env: Map[String, String]): Unit = {
    val msgs = rpc(launcher, project, env, Seq(call("find_symbol", "query" -> "Fixture")))
    val instructions = msgs
      .find(m => m.obj.get("result").exists(_.obj.contains("instructions")))
      .map(_("result")("instructions").str)
      .getOrElse(fail("initialize returned no instructions"))
    Seq("sentinel", "write", "baseHash", "compilable").foreach { word =>
      check(
        instructions.contains(word),
        s"the init prompt never mentions '$word', so no agent will find the edit path:\n$instructions"
      )
    }
    println("[ok] init prompt describes the edit path")
  }

  def assertRoundtrip(launcher: Path, project: Path, env: Map[String, String]): Unit = {
    val reads = rpc(
      launcher,
      project,
      env,
      Seq(call("annotated_source", "uri" -> "Fixture.scala", "format" -> "compilable", "sentinel" -> true))
    )
    val read = payload(reads.last)
    val buffer = read("source").str
    val hash = read("sha256").str
    check(buffer.contains("SEM:"), s"expected sentinel blocks in the buffer:\n$buffer")
    check(!buffer.contains("⟹"), s"compilable+sentinel must not emit ⟹ notes:\n$buffer")

    // Edit the annotated buffer exactly as an agent would: touch the code, leave the sentinels.
    val edited = buffer.replace("def sizes", "def lengths")
    val writes = rpc(
      launcher,
      project,
      env,
      Seq(call("annotated_source", "uri" -> "Fixture.scala", "write" -> edited, "baseHash" -> hash))
    )
    check(payload(writes.last)("written").bool, s"write was not accepted:\n${writes.last}")

    val onDisk = Files.readString(project.resolve("Fixture.scala"))
    check(onDisk.contains("def lengths"), s"the edit did not reach disk:\n$onDisk")
    check(!onDisk.contains("SEM:"), s"sentinels leaked to disk:\n$onDisk")
    check(!onDisk.contains("⟹"), s"annotations leaked to disk:\n$onDisk")
    println("[ok] annotated buffer roundtrips to disk without annotations")

    // Stale baseHash must be refused, not silently applied over the change just made.
    val stale = rpc(
      launcher,
      project,
      env,
      Seq(call("annotated_source", "uri" -> "Fixture.scala", "write" -> edited, "baseHash" -> hash))
    )
    val staleText = ujson.write(stale.last)
    check(
      staleText.contains("changed on disk") || staleText.contains("rejected"),
      s"a stale baseHash must be rejected, got:\n$staleText"
    )
    println("[ok] stale baseHash rejected")

    // A buffer read WITHOUT sentinel carries ⟹ notes that write mode cannot strip: refuse it
    // rather than persist annotations into the source.
    val leaked = rpc(
      launcher,
      project,
      env,
      Seq(call("annotated_source", "uri" -> "Fixture.scala", "write" -> "object Fixture: // ⟹ : Int\n"))
    )
    val leakedText = ujson.write(leaked.last)
    check(
      leakedText.contains("error") || leakedText.contains("⟹"),
      s"a write still carrying ⟹ notes must be refused, got:\n$leakedText"
    )
    check(
      !Files.readString(project.resolve("Fixture.scala")).contains("⟹"),
      "the refused write reached disk anyway"
    )
    println("[ok] a write carrying ⟹ notes is refused")
  }

  def main(args: Array[String]): Unit = {
    check(Files.exists(Installer), s"installer not found at $Installer")
    check(Files.exists(LocalJar), s"build the jar first: ./mill mcp.assembly (expected $LocalJar)")

    val sandbox = Files.createTempDirectory("scalasemantic-edit-guard-test")
    val home = Files.createDirectories(sandbox.resolve("home"))
    val project = fixtureProject(sandbox)
    val env = Map(
      "HOME" -> home.toString,
      "SCALASEMANTIC_TEST" -> "1",
      "SCALASEMANTIC_JAR" -> LocalJar.toString,
      "SCALASEMANTIC_SELF_SRC" -> Installer.toString
    )
    val launcher = project.resolve("scalasemantic-mcp.sh")

    val installCmd = Seq("sh", "-c", s"cat ${Installer.toString} | sh -s -- --project")
    val (code, out, err) = run(installCmd, project, env)
    check(code == 0, s"install exited $code\n--- stdout ---\n$out\n--- stderr ---\n$err")
    check(Files.exists(launcher), s"launcher missing after install: $launcher")

    val settings = Files.readString(project.resolve(".claude/settings.json"))
    check(
      settings.contains("Edit") && settings.contains("Write"),
      s"the guard is not registered for edit tools, so it can never fire:\n$settings"
    )
    println("[ok] installed, guard registered for edit tools")

    assertEditReminder(project)
    assertQuietElsewhere(project)
    assertReadsStillDenied(project)
    assertInstructionsSteer(launcher, project, env)
    assertRoundtrip(launcher, project, env)
    assertMatcherUpgrade(project, launcher, env)
    assertStrictDenies(project, launcher, env)

    rmTree(sandbox)
    check(!Files.exists(sandbox), s"sandbox not removed: $sandbox")
    println("[PASS] edit-guard test")
  }
}
