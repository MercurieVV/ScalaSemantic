package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Presentation-compiler-backed MCP tools, split out of `McpSuite`: each test here boots a real
  * Metals-style presentation compiler (`PresentationCompilerBackend.useCurrentJvm`), which is slow
  * enough that running it per-mutant under stryker4s (which reruns the whole filtered test class
  * against every mutant) hung/crashed the mutation run. `McpSuite` stays PC-free so
  * `--test-filter McpSuite` in scripts/run-stryker.sh only exercises cheap, fast tests.
  */
class McpPcSuite extends munit.FunSuite:

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private def req(
      method: String,
      params: ujson.Value,
      id: ujson.Value = ujson.Num(1)
  ): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)

  private def currentRuntimeClasspath: String =
    val fromProp = System
      .getProperty("java.class.path", "")
      .split(java.io.File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)
    val fromLoaders = Iterator
      .unfold(Thread.currentThread.getContextClassLoader: ClassLoader)(loader =>
        Option(loader).map(current => current -> current.getParent)
      )
      .collect { case urls: java.net.URLClassLoader => urls.getURLs.toSeq }
      .flatten
      .flatMap(url => scala.util.Try(java.nio.file.Paths.get(url.toURI).toString).toOption)
    (fromProp ++ fromLoaders).toVector.distinct.mkString(java.io.File.pathSeparator)

  test("extract_method_plan renders the new signature and the replacing call") {
    // A buffer with a method body and locals; lives on disk so the PC can resolve its path.
    val root = java.nio.file.Files.createTempDirectory("mcp-extract").nn
    val file = root.resolve("Calc.scala").nn
    val source =
      """package demo
        |
        |object Calc:
        |  def run(n: Int): Int =
        |    val a: Int = n + 1
        |    val b: Int = a * 2
        |    val c: Int = b + a
        |    c
        |""".stripMargin
    java.nio.file.Files.writeString(file, source)

    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val pcTools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty), Some(backend)), root)
      val resp = Mcp.handle(
        req(
          "tools/call",
          ujson.Obj(
            "name" -> "extract_method_plan",
            "arguments" -> ujson.Obj(
              "uri" -> "Calc.scala",
              "startLine" -> 5,
              "startCharacter" -> 0,
              "endLine" -> 7,
              "endCharacter" -> 0,
              "methodName" -> "compute",
              "source" -> source
            )
          )
        ),
        pcTools
      )
      val r = ujson.read(resp.getOrElse(fail("no response"))("result")("content")(0)("text").str)
      assertEquals(r("signature").str, "def compute(a: Int): Int")
      assertEquals(r("call").str, "val c = compute(a)")
      assertEquals(r("enclosingMethod").str, "run")
    }
  }

  test("launcher smoke applies module-aware classpath metadata to live buffers") {
    val root = java.nio.file.Files.createTempDirectory("mcp-launcher").nn
    val appDir = root.resolve("app").nn
    val srcDir = appDir.resolve("src/main/scala").nn
    val libSrcDir = root.resolve("lib-src/ext").nn
    val libClasses = root.resolve("lib-classes").nn
    val metaDir = root.resolve(".scala-semantic").nn
    java.nio.file.Files.createDirectories(srcDir)
    java.nio.file.Files.createDirectories(libSrcDir)
    java.nio.file.Files.createDirectories(libClasses)
    java.nio.file.Files.createDirectories(metaDir)

    val external = libSrcDir.resolve("External.java").nn
    java.nio.file.Files.writeString(
      external,
      """|package ext;
         |public final class External {
         |  public static String name() { return "ok"; }
         |}
         |""".stripMargin
    )
    @SuppressWarnings(Array("org.wartremover.warts.Null"))
    def compileExternal(): Unit =
      val javac = javax.tools.ToolProvider.getSystemJavaCompiler
      assert(javac != null, "launcher smoke requires a JDK with javac")
      assertEquals(javac.run(null, null, null, "-d", libClasses.toString, external.toString), 0)

    compileExternal()

    val uri = "app/src/main/scala/Widget.scala"
    val moduleClasspath =
      (libClasses.toString +: currentRuntimeClasspath
        .split(java.io.File.pathSeparator)
        .iterator
        .filter(_.nonEmpty)
        .toVector).distinct
    val source =
      """|package demo
         |
         |import ext.External
         |
         |class Widget:
         |  def value = External.name()
         |""".stripMargin
    java.nio.file.Files.writeString(root.resolve(uri), source)
    val cpFile = metaDir.resolve("classpath-sbt.json").nn
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "sbt",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "app",
              "baseDir" -> "app",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr.from(moduleClasspath.map(ujson.Str(_)))
            )
          )
        )
      )
    )

    val fakeBin = root.resolve("fake-bin").nn
    java.nio.file.Files.createDirectories(fakeBin)
    val fakeCs = fakeBin.resolve("cs").nn
    java.nio.file.Files.writeString(
      fakeCs,
      s"""|#!/usr/bin/env sh
          |set -eu
          |while [ "$$#" -gt 0 ] && [ "$$1" != "--" ]; do shift; done
          |[ "$$#" -gt 0 ] && shift
          |exec "${sys.props(
           "java.home"
         )}/bin/java" -cp "$$SCALASEMANTIC_TEST_CP" com.github.mercurievv.scalasemantic.mcpServer "$$@"
          |""".stripMargin
    )
    val _ = fakeCs.toFile.setExecutable(true)

    val script = java.nio.file.Paths.get("scripts/scalasemantic-mcp.sh").toAbsolutePath.nn
    val logFile = root.resolve("mcp.log").nn
    val builder =
      ProcessBuilder("sh", script.toString, "serve", root.toString, cpFile.toString, "--log")
        .directory(java.io.File("."))
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
    val processEnv = builder.environment()
    processEnv.put(
      "PATH",
      fakeBin.toString + java.io.File.pathSeparator + processEnv.getOrDefault("PATH", "")
    )
    processEnv.put("SCALASEMANTIC_TEST_CP", currentRuntimeClasspath)
    processEnv.put("SCALASEMANTIC_LOG_FILE", logFile.toString)
    val process = builder.start()

    process.getOutputStream.write(
      (ujson.write(
        req(
          "tools/call",
          ujson.Obj(
            "name" -> "type_at_position",
            "arguments" -> ujson.Obj(
              "uri" -> uri,
              "line" -> 5,
              "character" -> 24,
              "source" -> source
            )
          )
        )
      ) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    process.getOutputStream.close()
    val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val stdout =
      String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val stderr =
      String(process.getErrorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val log =
      if java.nio.file.Files.exists(logFile) then java.nio.file.Files.readString(logFile) else ""
    assert(finished, stderr)
    assertEquals(process.exitValue(), 0, stderr)

    val response =
      ujson.read(stdout.linesIterator.filter(_.nonEmpty).toList.headOption.getOrElse(""))
    val body = ujson.read(response("result")("content")(0)("text").str)
    assert(
      body.obj.contains("type"),
      s"stdout=$stdout\nstderr=$stderr\nlog=$log\nbody=${body.render()}"
    )
    assertEquals(body("name").str, "name")
    assert(body("symbol").str.startsWith("ext/External#name"), body.render())
  }

  test(
    "PC-backed tools answer on an uncompiled buffer: type_at_position (PC-only) + method_signature (overlay)"
  ) {
    // A buffer NOT in the disk index, whose tail does not typecheck. Lives on disk under a root so
    // the PC can resolve its path; the tools key the buffer by the root-relative uri.
    val root = java.nio.file.Files.createTempDirectory("mcp-pc").nn
    val file = root.resolve("Widget.scala").nn
    val source =
      """package demo
        |
        |class Widget:
        |  def area(w: Int): Int = w * 2
        |
        |val broken: Int = "oops"
        |""".stripMargin
    java.nio.file.Files.writeString(file, source)

    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val pcTools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty), Some(backend)), root)
      def pcCall(tool: String, args: ujson.Value): ujson.Value =
        val resp =
          Mcp.handle(req("tools/call", ujson.Obj("name" -> tool, "arguments" -> args)), pcTools)
        ujson.read(resp.getOrElse(fail("no response"))("result")("content")(0)("text").str)

      // type_at_position (PC-only): without `source`, the empty disk index knows nothing.
      val cold = pcCall(
        "type_at_position",
        ujson.Obj("uri" -> "Widget.scala", "line" -> 3, "character" -> 6)
      )
      assertEquals(cold("found").bool, false)
      // With `source`, the PC regenerates SemanticDB and the position resolves — despite the error.
      val live = pcCall(
        "type_at_position",
        ujson.Obj("uri" -> "Widget.scala", "line" -> 3, "character" -> 6, "source" -> source)
      )
      assertEquals(live("name").str, "area")
      assertEquals(live("type").str, "Int")

      // method_signature (overlay): resolve the buffer's symbol, then read its signature live.
      val area = "demo/Widget#area()."
      assertEquals(
        pcCall("method_signature", ujson.Obj("symbol" -> area)).obj.get("found"),
        Some(ujson.Bool(false))
      )
      val sig =
        pcCall(
          "method_signature",
          ujson.Obj("symbol" -> area, "uri" -> "Widget.scala", "source" -> source)
        )
      assertEquals(sig("signature").str, "def area(w: Int): Int")
    }
  }
