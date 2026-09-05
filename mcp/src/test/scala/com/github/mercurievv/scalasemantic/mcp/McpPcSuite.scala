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

  test("document_outline reads a never-compiled buffer and narrows it to one declaration") {
    val root = java.nio.file.Files.createTempDirectory("mcp-outline").nn
    val file = root.resolve("Shapes.scala").nn
    val source =
      """package demo
        |
        |class Circle:
        |  def area(r: Double): Double = r * r
        |  def name(): String = "circle"
        |
        |class Square:
        |  def area(s: Double): Double = s * s
        |""".stripMargin
    java.nio.file.Files.writeString(file, source)

    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val pcTools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty), Some(backend)), root)
      def outline(args: ujson.Obj): ujson.Value =
        val resp = Mcp.handle(
          req("tools/call", ujson.Obj("name" -> "document_outline", "arguments" -> args)),
          pcTools
        )
        ujson.read(resp.getOrElse(fail("no response"))("result")("content")(0)("text").str)

      // Nothing is compiled and the disk index is empty, so without `source` there is no outline.
      assertEquals(outline(ujson.Obj("uri" -> "Shapes.scala"))("found").bool, false)

      val full = outline(ujson.Obj("uri" -> "Shapes.scala", "source" -> source))
      assertEquals(full("liveSource").bool, true)
      assert(!full.obj.contains("filtered"), full.render())
      assertEquals(
        full("outline").arr.map(_("name").str).toList.sorted,
        List("Circle", "Square")
      )

      val narrowed = outline(
        ujson.Obj("uri" -> "Shapes.scala", "source" -> source, "query" -> "name")
      )
      assertEquals(narrowed("filtered").bool, true)
      assertEquals(
        narrowed("outline").arr.map(_("name").str).toList,
        List("Circle"),
        "context, not the match"
      )
      assertEquals(
        narrowed("outline")(0)("children").arr.map(_("name").str).toList,
        List("name")
      )
    }
  }

  test("launcher smoke discovers module-aware classpath metadata for live buffers") {
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
      assert(
        javac != null,
        "launcher smoke requires a JDK with javac"
      ) // scalafix:ok DisableSyntax.null
      assertEquals(
        // in/out/err null means "use System.in/out/err" per javax.tools.JavaCompiler#run's contract.
        javac.run(
          null,
          null,
          null,
          "-d",
          libClasses.toString,
          external.toString
        ), // scalafix:ok DisableSyntax.null
        0
      )

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
    val fakeJava = fakeBin.resolve("java").nn
    java.nio.file.Files.writeString(
      fakeJava,
      s"""|#!/usr/bin/env sh
            |set -eu
            |[ "$$1" = "-jar" ] && shift 2
            |exec "${sys.props(
           "java.home"
         )}/bin/java" -cp "$$SCALASEMANTIC_TEST_CP" com.github.mercurievv.scalasemantic.mcpServer "$$@"
            |""".stripMargin
    )
    val _ = fakeJava.toFile.setExecutable(true)
    val cache = root.resolve("cache").nn
    val jarCache = cache.resolve("scalasemantic-mcp").nn
    java.nio.file.Files.createDirectories(jarCache)
    java.nio.file.Files.writeString(jarCache.resolve("scalasemantic-mcp-local.jar"), "")

    val script = java.nio.file.Paths.get("scripts/scalasemantic-mcp.sh").toAbsolutePath.nn
    val logFile = root.resolve("mcp.log").nn
    val builder =
      ProcessBuilder("sh", script.toString, "serve", root.toString, "--log")
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
    processEnv.put("SCALASEMANTIC_VERSION", "local")
    // ADR-0004 moved the jar from a cache directory to installed data under SCALASEMANTIC_HOME.
    processEnv.put("SCALASEMANTIC_HOME", jarCache.toString)
    val process = builder.start()

    val initializeReq = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id" -> ujson.Num(1),
      "method" -> "initialize",
      "params" -> ujson.Obj("protocolVersion" -> "2025-06-18")
    )
    val initializedNotification = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method" -> "notifications/initialized",
      "params" -> ujson.Obj()
    )
    val toolsCallReq = req(
      "tools/call",
      ujson.Obj(
        "name" -> "type_at_position",
        "arguments" -> ujson.Obj(
          "uri" -> uri,
          "line" -> 5,
          "character" -> 24,
          "source" -> source
        )
      ),
      id = ujson.Num(2)
    )

    val os = process.getOutputStream.nn
    os.write((ujson.write(initializeReq) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    os.write(
      (ujson.write(initializedNotification) + "\n")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    os.write((ujson.write(toolsCallReq) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    os.close()

    val finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val stdout =
      String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val stderr =
      String(process.getErrorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val log =
      if java.nio.file.Files.exists(logFile) then java.nio.file.Files.readString(logFile) else ""
    assert(finished, stderr)
    assertEquals(process.exitValue(), 0, stderr)

    val lines = stdout.linesIterator.filter(_.nonEmpty).toList
    val initResp = ujson.read(lines.headOption.getOrElse(""))
    assert(initResp.obj.contains("result"), s"Invalid init response: $initResp")

    val callResp = ujson.read(lines.lift(1).getOrElse(""))
    val body = ujson.read(callResp("result")("content")(0)("text").str)
    assert(
      body.obj.contains("type"),
      s"stdout=$stdout\nstderr=$stderr\nlog=$log\nbody=${body.render()}"
    )
    assertEquals(body("name").str, "name")
    assert(body("symbol").str.startsWith("ext/External#name"), body.render())
  }

  test("launcher smoke switches workspace root statefully") {
    val temp = java.nio.file.Files.createTempDirectory("mcp-root-switch").nn
    val rootA = temp.resolve("root-a").nn
    val rootB = temp.resolve("root-b").nn
    java.nio.file.Files.createDirectories(rootA)
    java.nio.file.Files.createDirectories(rootB)

    def findSemanticdb(name: String, marker: String): java.nio.file.Path =
      scala.util.Using.resource(java.nio.file.Files.walk(java.nio.file.Paths.get("out"))) {
        stream =>
          stream
            .filter(p => java.nio.file.Files.isRegularFile(p))
            .filter(p => p.getFileName.toString == name)
            .filter(p => p.toString.contains(marker))
            .findFirst()
            .orElseThrow(() => AssertionError(s"missing semanticdb fixture: $marker/$name"))
            .nn
      }

    def copySemanticdb(from: java.nio.file.Path, root: java.nio.file.Path): Unit =
      val rel = from.subpath(from.getNameCount - 8, from.getNameCount).nn
      val dest = root.resolve("META-INF/semanticdb").resolve(rel).nn
      java.nio.file.Files.createDirectories(dest.getParent)
      val _ =
        java.nio.file.Files.copy(from, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

    copySemanticdb(
      findSemanticdb("DuplicationAnalyzer.scala.semanticdb", "analysis/compile"),
      rootA
    )
    copySemanticdb(findSemanticdb("Mcp.scala.semanticdb", "mcp/compile"), rootB)

    val fakeBin = temp.resolve("fake-bin").nn
    java.nio.file.Files.createDirectories(fakeBin)
    val fakeJava = fakeBin.resolve("java").nn
    java.nio.file.Files.writeString(
      fakeJava,
      s"""|#!/usr/bin/env sh
            |set -eu
            |[ "$$1" = "-jar" ] && shift 2
            |exec "${sys.props(
           "java.home"
         )}/bin/java" -cp "$$SCALASEMANTIC_TEST_CP" com.github.mercurievv.scalasemantic.mcpServer "$$@"
            |""".stripMargin
    )
    val _ = fakeJava.toFile.setExecutable(true)
    val cache = temp.resolve("cache").nn
    val jarCache = cache.resolve("scalasemantic-mcp").nn
    java.nio.file.Files.createDirectories(jarCache)
    java.nio.file.Files.writeString(jarCache.resolve("scalasemantic-mcp-local.jar"), "")

    val script = java.nio.file.Paths.get("scripts/scalasemantic-mcp.sh").toAbsolutePath.nn
    val builder =
      ProcessBuilder("sh", script.toString, "serve", rootA.toString)
        .directory(java.io.File("."))
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
    val processEnv = builder.environment()
    processEnv.put(
      "PATH",
      fakeBin.toString + java.io.File.pathSeparator + processEnv.getOrDefault("PATH", "")
    )
    processEnv.put("SCALASEMANTIC_TEST_CP", currentRuntimeClasspath)
    processEnv.put("SCALASEMANTIC_VERSION", "local")
    // ADR-0004 moved the jar from a cache directory to installed data under SCALASEMANTIC_HOME.
    processEnv.put("SCALASEMANTIC_HOME", jarCache.toString)
    val process = builder.start()

    val requests = List(
      ujson.Obj(
        "jsonrpc" -> "2.0",
        "id" -> ujson.Num(1),
        "method" -> "initialize",
        "params" -> ujson.Obj("protocolVersion" -> "2025-06-18")
      ),
      req(
        "tools/call",
        ujson.Obj(
          "name" -> "find_symbol",
          "arguments" -> ujson.Obj("query" -> "DuplicationAnalyzer", "exact" -> true)
        ),
        id = ujson.Num(2)
      ),
      req(
        "tools/call",
        ujson
          .Obj("name" -> "set_workspace_root", "arguments" -> ujson.Obj("path" -> rootB.toString)),
        id = ujson.Num(3)
      ),
      req(
        "tools/call",
        ujson.Obj(
          "name" -> "find_symbol",
          "arguments" -> ujson.Obj("query" -> "Mcp", "exact" -> true)
        ),
        id = ujson.Num(4)
      ),
      req(
        "tools/call",
        ujson.Obj(
          "name" -> "find_symbol",
          "arguments" -> ujson.Obj("query" -> "DuplicationAnalyzer", "exact" -> true)
        ),
        id = ujson.Num(5)
      ),
      req(
        "tools/call",
        ujson.Obj("name" -> "get_workspace_root", "arguments" -> ujson.Obj()),
        id = ujson.Num(6)
      )
    )

    val os = process.getOutputStream.nn
    requests.foreach(r =>
      os.write((ujson.write(r) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )
    os.close()

    val finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val stdout =
      String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val stderr =
      String(process.getErrorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assert(finished, stderr)
    assertEquals(process.exitValue(), 0, stderr)

    val lines = stdout.linesIterator.filter(_.nonEmpty).map(ujson.read(_)).toVector
    assertEquals(lines.size, requests.size, s"stdout=$stdout\nstderr=$stderr")
    def body(index: Int): ujson.Value =
      ujson.read(lines(index)("result")("content")(0)("text").str)

    val rootAFind = body(1)
    assert(
      rootAFind("symbols").arr.exists(_("symbol").str.endsWith("/DuplicationAnalyzer.")),
      rootAFind.render()
    )
    val setRoot = body(2)
    assertEquals(setRoot("root").str, rootB.toString)
    assertEquals(setRoot("cached").bool, false)
    val rootBFind = body(3)
    assert(rootBFind("symbols").arr.exists(_("symbol").str.endsWith("/Mcp.")), rootBFind.render())
    val staleFind = body(4)
    assertEquals(staleFind("count").num, 0.0)
    val currentRoot = body(5)
    assertEquals(currentRoot("root").str, rootB.toString)
  }

  test("launcher smoke with missing/empty classpath falls back to index-only (PC disabled)") {
    val root = java.nio.file.Files.createTempDirectory("mcp-launcher-empty").nn
    val appDir = root.resolve("app").nn
    val srcDir = appDir.resolve("src/main/scala").nn
    java.nio.file.Files.createDirectories(srcDir)

    val uri = "app/src/main/scala/Widget.scala"
    val source =
      """|package demo
         |
         |import ext.External
         |
         |class Widget:
         |  def value = External.name()
         |""".stripMargin
    java.nio.file.Files.writeString(root.resolve(uri), source)

    val cpFile = root.resolve("nonexistent-classpath.json").nn

    val fakeBin = root.resolve("fake-bin").nn
    java.nio.file.Files.createDirectories(fakeBin)
    val fakeJava = fakeBin.resolve("java").nn
    java.nio.file.Files.writeString(
      fakeJava,
      s"""|#!/usr/bin/env sh
            |set -eu
            |[ "$$1" = "-jar" ] && shift 2
            |exec "${sys.props(
           "java.home"
         )}/bin/java" -cp "$$SCALASEMANTIC_TEST_CP" com.github.mercurievv.scalasemantic.mcpServer "$$@"
            |""".stripMargin
    )
    val _ = fakeJava.toFile.setExecutable(true)
    val cache = root.resolve("cache").nn
    val jarCache = cache.resolve("scalasemantic-mcp").nn
    java.nio.file.Files.createDirectories(jarCache)
    java.nio.file.Files.writeString(jarCache.resolve("scalasemantic-mcp-local.jar"), "")

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
    processEnv.put("SCALASEMANTIC_VERSION", "local")
    // ADR-0004 moved the jar from a cache directory to installed data under SCALASEMANTIC_HOME.
    processEnv.put("SCALASEMANTIC_HOME", jarCache.toString)
    val process = builder.start()

    val initializeReq = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id" -> ujson.Num(1),
      "method" -> "initialize",
      "params" -> ujson.Obj("protocolVersion" -> "2025-06-18")
    )
    val initializedNotification = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method" -> "notifications/initialized",
      "params" -> ujson.Obj()
    )
    val toolsCallReq = req(
      "tools/call",
      ujson.Obj(
        "name" -> "type_at_position",
        "arguments" -> ujson.Obj(
          "uri" -> uri,
          "line" -> 5,
          "character" -> 24,
          "source" -> source
        )
      ),
      id = ujson.Num(2)
    )

    val os = process.getOutputStream.nn
    os.write((ujson.write(initializeReq) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    os.write(
      (ujson.write(initializedNotification) + "\n")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    os.write((ujson.write(toolsCallReq) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    os.close()

    val finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val stdout =
      String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val stderr =
      String(process.getErrorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assert(finished, stderr)
    assertEquals(process.exitValue(), 0, stderr)

    val lines = stdout.linesIterator.filter(_.nonEmpty).toList
    val initResp = ujson.read(lines.headOption.getOrElse(""))
    assert(initResp.obj.contains("result"), s"Invalid init response: $initResp")

    val callResp = ujson.read(lines.lift(1).getOrElse(""))
    val body = ujson.read(callResp("result")("content")(0)("text").str)
    assertEquals(body("found").bool, false)
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
