#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// End-to-end launcher smoke test for ScalaSemantic MCP server against a real Mill project.
// Unlike scripts/smoke-test.sh (synthetic scala-cli fixture, PC-backed type_at_position), this
// dogfoods the launcher directly against THIS repo's own build.mill — Mill's own DSL/config code,
// compiled by Mill's meta-build into out/mill-build/.../wrapped/build_/build.mill.semanticdb — run
// from the project root, proving index-based tools work against real Mill build-script code (not
// synthetic fixtures, and not regular application source under core/analysis/mcp).

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.file.*
import scala.jdk.CollectionConverters.*

object SmokeTestMill {

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
    // Must be run from the repo root (this project's ./mill build lives there).
    val repoRoot = Paths.get(".").toAbsolutePath.normalize()
    if (!Files.exists(repoRoot.resolve("build.mill"))) {
      System.err.println(s"Error: $repoRoot has no build.mill — run this script from the repo root.")
      sys.exit(1)
    }
    val assemblyJar = repoRoot.resolve("out/mcp/assembly.dest/out.jar")

    if (!Files.exists(assemblyJar)) {
      System.err.println(s"Error: $assemblyJar missing. Run './mill mcp.assembly' first.")
      sys.exit(1)
    }

    val semanticdbCount = findFiles(repoRoot.resolve("out"), _.getFileName.toString.endsWith(".semanticdb")).size
    if (semanticdbCount == 0) {
      System.err.println(s"Error: no *.semanticdb files under ${repoRoot.resolve("out")}. Run './mill __.compile' first.")
      sys.exit(1)
    }
    println(s"Found $semanticdbCount emitted SemanticDB files (Mill-generated).")

    val buildMillSemanticdb =
      findFiles(repoRoot.resolve("out/mill-build"), _.getFileName.toString == "build.mill.semanticdb").headOption
    buildMillSemanticdb match {
      case None =>
        System.err.println(
          s"Error: no build.mill.semanticdb under ${repoRoot.resolve("out/mill-build")}. " +
            "Run './mill __.compile' (or any mill command) first to trigger the meta-build compile."
        )
        sys.exit(1)
      case Some(p) => println(s"Found Mill meta-build SemanticDB for build.mill: $p")
    }

    val cacheDir = Paths.get(sys.env.getOrElse("XDG_CACHE_HOME", sys.env("HOME") + "/.cache"), "scalasemantic-mcp")
    Files.createDirectories(cacheDir)
    println("Copying local assembly jar to cache...")
    Files.copy(assemblyJar, cacheDir.resolve("scalasemantic-mcp-local.jar"), StandardCopyOption.REPLACE_EXISTING)

    // Both target symbols live in build.mill itself (Mill's own DSL/config, compiled by the
    // meta-build into wrapped/build_/build.mill), NOT in regular application source under
    // core/analysis/mcp — that's the whole point of this test.
    val smokeTestSymbol = "build_/package_#smokeTest()."
    val prePushSymbol = "build_/package_#prePush()."
    val requests = Seq(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
      """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
      // `def smokeTest() = Task.Command { ... }` in build.mill
      s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"method_signature","arguments":{"symbol":"$smokeTestSymbol"}}}""",
      s"""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_usages","arguments":{"symbol":"$smokeTestSymbol"}}}""",
      // `def prePush() = Task.Command { ... smokeTest()() ... }` in build.mill — calls smokeTest, so
      // find_usages on smokeTest (above) and call_path from prePush to smokeTest both cross-check
      // the same real build.mill call edge.
      s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"call_path","arguments":{"from":"$prePushSymbol","to":"$smokeTestSymbol"}}}"""
    )

    println("Launching server with root = repo root (real Mill project, no explicit classpath arg)...")

    val launcher = repoRoot.resolve("scripts/scalasemantic-mcp.sh").toString
    val pb = new java.lang.ProcessBuilder(launcher, "serve", repoRoot.toString, "--log")
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

    // Parse each JSON-RPC response line and pull out the tool's own JSON payload (it comes back
    // as a string inside result.content[0].text), so this checks the *actual* structured answer
    // rather than grepping raw text for a substring.
    val responsesById: Map[Int, ujson.Value] =
      stdout
        .linesIterator
        .flatMap(line => scala.util.Try(ujson.read(line)).toOption)
        .flatMap(msg => msg.obj.get("id").map(_.num.toInt -> msg))
        .toMap

    def toolResult(id: Int): ujson.Value =
      responsesById.get(id) match {
        case None => fail(s"did not receive any JSON-RPC response for id=$id", stdout, stderr)
        case Some(msg) =>
          msg.obj.get("error").foreach(err => fail(s"tools/call id=$id returned an error: $err", stdout, stderr))
          val text = msg("result")("content")(0)("text").str
          ujson.read(text)
      }

    val methodSigSymbol = smokeTestSymbol
    val methodSigResult = toolResult(2)
    val expectedSignature = "def smokeTest(): Command[Unit]"
    if (methodSigResult("symbol").str != methodSigSymbol)
      fail(s"method_signature echoed wrong symbol: ${methodSigResult("symbol")}", stdout, stderr)
    if (methodSigResult("signature").str != expectedSignature)
      fail(
        s"expected method_signature($methodSigSymbol) to return exactly '$expectedSignature', got: ${methodSigResult("signature")}",
        stdout,
        stderr
      )
    println(s"method_signature OK: ${methodSigResult("signature").str}")

    // find_usages on the same build.mill symbol: prePush() calls smokeTest(), so exactly one
    // real call site should turn up in build.mill.
    val findUsagesResult = toolResult(3)
    if (findUsagesResult("symbol").str != smokeTestSymbol)
      fail(s"find_usages echoed wrong symbol: ${findUsagesResult("symbol")}", stdout, stderr)
    val referenceCount = findUsagesResult("referenceCount").num.toInt
    if (referenceCount < 1)
      fail(s"expected find_usages($smokeTestSymbol) to find at least 1 usage, got $referenceCount", stdout, stderr)
    val references = findUsagesResult("references").arr.map(_.str)
    if (!references.exists(_.contains("build.mill")))
      fail(s"expected a find_usages reference inside build.mill, got: $references", stdout, stderr)
    println(s"find_usages OK: $referenceCount reference(s), including ${references.find(_.contains("build.mill")).get}")

    // call_path: prePush() → smokeTest() is a real one-hop call edge in build.mill.
    val callPathResult = toolResult(4)
    if (!callPathResult("reachable").bool)
      fail(s"expected call_path($prePushSymbol -> $smokeTestSymbol) to be reachable, got: $callPathResult", stdout, stderr)
    val path = callPathResult("path").arr.map(_.str)
    if (path != Seq("prePush", "smokeTest"))
      fail(s"expected call_path to resolve exactly [prePush, smokeTest], got: $path", stdout, stderr)
    println(s"call_path OK: ${path.mkString(" -> ")}")

    println("=== Mill E2E Smoke Test Passed Successfully ===")
  }
}
