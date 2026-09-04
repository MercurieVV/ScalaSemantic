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

  /** Runs a command, returning (exitCode, stdout, stderr). Never inherits stdin: the launcher execs
    * a server that would otherwise block reading it.
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

  def main(args: Array[String]): Unit = {
    check(Files.exists(Launcher), s"launcher not found at $Launcher")
    testPrefersLocalJar()
    testUseReleaseRemovesLocalJar()
    println("[ok] all local-channel tests passed")
  }
}
