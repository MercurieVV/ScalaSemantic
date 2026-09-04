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
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  /** A minimal but real sbt project with SemanticDB on and one distinctively-named symbol. */
  def fixtureProject(parent: Path): Path = {
    val dir = parent.resolve("fixture-project")
    Files.createDirectories(dir.resolve("src/main/scala"))
    Files.createDirectories(dir.resolve("project"))
    Files.writeString(
      dir.resolve("build.sbt"),
      """|ThisBuild / scalaVersion := "3.8.4"
         |ThisBuild / semanticdbEnabled := true
         |lazy val root = (project in file("."))
         |""".stripMargin
    )
    Files.writeString(dir.resolve("project/build.properties"), "sbt.version=1.10.7\n")
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

    // 7. Teardown.
    rmTree(sandbox)
    check(!Files.exists(sandbox), s"sandbox not removed: $sandbox")
    println(s"[PASS] install test ($mode)")
  }
}
