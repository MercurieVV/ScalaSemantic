#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// End-to-end launcher smoke test proving ScalaSemantic MCP tools work against this repo's own
// standalone scala-cli scripts under scripts/ (e.g. smoke-test-mill.sc itself) — a THIRD kind of
// target distinct from scripts/smoke-test.sh (synthetic scala-cli fixture, PC-backed) and
// scripts/smoke-test-mill.sc (Mill's own build.mill DSL code). Run from the project root.
//
// Key discovery this test locks in: scala-cli writes semanticdb under the HIDDEN
// scripts/.scala-build/ directory. SemanticIndex.fromProject skips hidden directories while
// *walking*, so pointing the server root at scripts/ (visible) yields an empty index — but
// pointing it directly AT scripts/.scala-build (the hidden dir itself, as the start path rather
// than something discovered mid-walk) works. This test asserts that behavior, not just tool output.

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.file.*
import scala.jdk.CollectionConverters.*

object SmokeTestScripts {

  def fail(msg: String, stdout: String, stderr: String): Nothing = {
    System.err.println(s"Error: $msg")
    System.err.println("--- stdout ---")
    System.err.println(stdout)
    System.err.println("--- stderr ---")
    System.err.println(stderr)
    sys.exit(1)
  }

  def findFiles(root: Path, matches: Path => Boolean): Vector[Path] = {
    if (!Files.exists(root)) Vector.empty
    else {
      val stream = Files.walk(root)
      try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && matches(p)).toVector
      finally stream.close()
    }
  }

  def main(args: Array[String]): Unit = {
    // Must be run from the repo root (scripts/ is a direct child).
    val repoRoot = Paths.get(".").toAbsolutePath.normalize()
    val scriptsDir = repoRoot.resolve("scripts")
    if (!Files.isDirectory(scriptsDir)) {
      System.err.println(s"Error: $scriptsDir does not exist — run this script from the repo root.")
      sys.exit(1)
    }
    val assemblyJar = repoRoot.resolve("out/mcp/assembly.dest/out.jar")
    if (!Files.exists(assemblyJar)) {
      System.err.println(s"Error: $assemblyJar missing. Run './mill mcp.assembly' first.")
      sys.exit(1)
    }

    val targetScript = scriptsDir.resolve("smoke-test-mill.sc")
    println(s"Compiling $targetScript with --semanticdb to (re)generate its SemanticDB...")
    val compileProc =
      new java.lang.ProcessBuilder("scala-cli", "compile", "--semanticdb", targetScript.toString)
        .redirectOutput(java.lang.ProcessBuilder.Redirect.INHERIT)
        .redirectError(java.lang.ProcessBuilder.Redirect.INHERIT)
        .start()
    val compileExit = compileProc.waitFor()
    if (compileExit != 0) {
      System.err.println(
        s"Error: scala-cli compile --semanticdb failed with exit code $compileExit"
      )
      sys.exit(1)
    }

    val scalaBuildDir = scriptsDir.resolve(".scala-build")
    val semanticdbFiles =
      findFiles(scalaBuildDir, _.getFileName.toString == "smoke-test-mill.sc.semanticdb")
    if (semanticdbFiles.isEmpty) {
      System.err.println(
        s"Error: no smoke-test-mill.sc.semanticdb found under $scalaBuildDir after compile."
      )
      sys.exit(1)
    }
    println(s"Found scala-cli-emitted SemanticDB: ${semanticdbFiles.head}")

    // scala-cli wraps the script's top-level object in a synthetic file-derived package
    // (`_empty_/smoke$minustest$minusmill$_`), NOT `_empty_` directly — confirmed via find_symbol.
    val failSymbol = "_empty_/smoke$minustest$minusmill$_#SmokeTestMill.fail()."
    val requests = Seq(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
      """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
      s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"method_signature","arguments":{"symbol":"$failSymbol"}}}""",
      s"""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_usages","arguments":{"symbol":"$failSymbol"}}}"""
    )

    println(
      s"Launching server with root = $scalaBuildDir (hidden dir passed directly as the root)..."
    )
    val launcher = repoRoot.resolve("scripts/scalasemantic-mcp.sh").toString
    val pb = new java.lang.ProcessBuilder(launcher, "serve", scalaBuildDir.toString, "--log")
    pb.environment().put("SCALASEMANTIC_VERSION", "local")
    val proc = pb.start()

    def drain(is: java.io.InputStream): (StringBuilder, Thread) = {
      val buf = new StringBuilder
      val reader = new BufferedReader(new InputStreamReader(is))
      val t = new Thread(() =>
        Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
          buf.append(line).append("\n")
        }
      )
      t.setDaemon(true)
      t.start()
      (buf, t)
    }
    val (stdoutBuf, stdoutThread) = drain(proc.getInputStream)
    val (stderrBuf, stderrThread) = drain(proc.getErrorStream)

    val stdinWriter = new OutputStreamWriter(proc.getOutputStream)
    requests.foreach { line =>
      stdinWriter.write(line)
      stdinWriter.write("\n")
      stdinWriter.flush()
    }
    Thread.sleep(4000)
    stdinWriter.close()
    proc.destroy()
    proc.waitFor()
    stdoutThread.join(2000)
    stderrThread.join(2000)

    val stdout = stdoutBuf.toString
    val stderr = stderrBuf.toString

    val responsesById: Map[Int, ujson.Value] =
      stdout.linesIterator
        .flatMap(line => scala.util.Try(ujson.read(line)).toOption)
        .flatMap(msg => msg.obj.get("id").map(_.num.toInt -> msg))
        .toMap

    def toolResult(id: Int): ujson.Value =
      responsesById.get(id) match {
        case None      => fail(s"did not receive any JSON-RPC response for id=$id", stdout, stderr)
        case Some(msg) =>
          msg.obj
            .get("error")
            .foreach(err => fail(s"tools/call id=$id returned an error: $err", stdout, stderr))
          val text = msg("result")("content")(0)("text").str
          ujson.read(text)
      }

    val methodSigResult = toolResult(2)
    val expectedSignature = "def fail(msg: String, stdout: String, stderr: String): Nothing"
    if (methodSigResult("symbol").str != failSymbol)
      fail(s"method_signature echoed wrong symbol: ${methodSigResult("symbol")}", stdout, stderr)
    if (methodSigResult("signature").str != expectedSignature)
      fail(
        s"expected method_signature($failSymbol) to return exactly '$expectedSignature', got: ${methodSigResult("signature")}",
        stdout,
        stderr
      )
    println(s"method_signature OK: ${methodSigResult("signature").str}")

    val findUsagesResult = toolResult(3)
    if (findUsagesResult("symbol").str != failSymbol)
      fail(s"find_usages echoed wrong symbol: ${findUsagesResult("symbol")}", stdout, stderr)
    val referenceCount = findUsagesResult("referenceCount").num.toInt
    if (referenceCount < 1)
      fail(
        s"expected find_usages($failSymbol) to find at least 1 usage, got $referenceCount",
        stdout,
        stderr
      )
    val definitions = findUsagesResult("definitions").arr.map(_.str)
    if (!definitions.exists(_.contains("smoke-test-mill.sc")))
      fail(
        s"expected a find_usages definition inside smoke-test-mill.sc, got: $definitions",
        stdout,
        stderr
      )
    val references = findUsagesResult("references").arr.map(_.str)
    if (!references.forall(_.contains("smoke-test-mill.sc")))
      fail(
        s"expected all find_usages references to be inside smoke-test-mill.sc, got: $references",
        stdout,
        stderr
      )
    println(s"find_usages OK: $referenceCount reference(s) in smoke-test-mill.sc")

    println("=== Scripts E2E Smoke Test Passed Successfully ===")
  }
}
