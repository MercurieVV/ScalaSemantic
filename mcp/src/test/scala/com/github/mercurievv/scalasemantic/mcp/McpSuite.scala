package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Phase 4: MCP protocol + tool wiring, driven through the pure `Mcp.handle` against the fixtures
  * index. Also pins the token-discipline contract: lean by default, structured under `detailed`.
  */
class McpSuite extends munit.FunSuite:

  // Default munit per-test timeout (30s) is too tight for smart_code_duplications (whole-project
  // scan) when 5 stryker4s test-runner JVMs compete for CPU concurrently during mutation testing.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val tools = Mcp.toolsFor(
    Analyzer(SemanticIndex.fromProject(".")),
    java.nio.file.Paths.get(".").toAbsolutePath.nn
  )

  private val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"
  private val Robot = "com/github/mercurievv/scalasemantic/fixtures/Robot#"
  private val Render = "com/github/mercurievv/scalasemantic/fixtures/Sample.render()."
  private val Show = "com/github/mercurievv/scalasemantic/fixtures/Show#"

  private def req(
      method: String,
      params: ujson.Value,
      id: ujson.Value = ujson.Num(1)
  ): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)

  /** Invoke a tool and parse the JSON carried in its text content block. */
  private def call(name: String, args: ujson.Value): ujson.Value =
    val resp = callResponse(name, args)
    val text = resp.getOrElse(fail("no response"))("result")("content")(0)("text").str
    ujson.read(text)

  private def callResponse(name: String, args: ujson.Value): Option[ujson.Value] =
    Mcp.handle(req("tools/call", ujson.Obj("name" -> name, "arguments" -> args)), tools)

  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private def jsonArray(value: ujson.Value): Vector[ujson.Value] =
    value.arr.toVector

  test("initialize echoes the protocol version, advertises the server, and ships instructions") {
    val r = Mcp.handle(req("initialize", ujson.Obj("protocolVersion" -> "2025-06-18")), tools).get
    assertEquals(r("result")("protocolVersion").str, "2025-06-18")
    assertEquals(r("result")("serverInfo")("name").str, Mcp.ServerName)
    // instructions must steer the model to prefer these tools over grep
    val instr = r("result")("instructions").str
    assert(instr.toLowerCase.contains("grep"), instr)
    assert(instr.contains("find_symbol"), instr)
  }

  test("initialize instructions describe the annotation-aware edit path") {
    val r = Mcp.handle(req("initialize", ujson.Obj("protocolVersion" -> "2025-06-18")), tools).get
    val instr = r("result")("instructions").str
    // Reading annotated is already covered above; editing is the part agents never find.
    assert(instr.contains("sentinel"), instr)
    // sentinel alone keeps the line-number gutter, which write mode does not strip — the writable
    // buffer is the compilable+sentinel pair, so the prompt must say so.
    assert(instr.contains("compilable"), instr)
    assert(instr.contains("write"), instr)
    assert(instr.contains("baseHash"), instr)
  }

  test("tools/list exposes all tools with schemas") {
    val r = Mcp.handle(req("tools/list", ujson.Obj()), tools).get
    val names = r("result")("tools").arr.map(_("name").str).toSet
    assertEquals(names.size, 27)
    assert(names.contains("value_flow"), names.toString)
    assert(names.contains("find_symbol"), names.toString)
    assert(names.contains("find_usages"), names.toString)
    assert(names.contains("call_path"), names.toString)
    assert(names.contains("method_call_hierarchy"), names.toString)
    assert(names.contains("structure"), names.toString)
    assert(names.contains("document_outline"), names.toString)
    assert(names.contains("annotated_source"), names.toString)
    assert(names.contains("symbol_source"), names.toString)
    assert(names.contains("source_around_position"), names.toString)
    assert(names.contains("rename_plan"), names.toString)
    assert(names.contains("batch_rename_plan"), names.toString)
    assert(names.contains("move_plan"), names.toString)
    assert(names.contains("extract_method_plan"), names.toString)
    assert(names.contains("smart_code_duplications"), names.toString)
    assert(names.contains("search_text"), names.toString)
    // every tool carries an object input schema
    assert(r("result")("tools").arr.forall(_("inputSchema")("type").str == "object"))
  }

  test("setup-generated client args use cwd root instead of setup-time absolute project path") {
    val root = java.nio.file.Files.createTempDirectory("ss-setup-config").nn
    com.github.mercurievv.scalasemantic.Launcher.run(
      Seq("setup", "--project", root.toString, "--client", "claude", "--skip-semanticdb-config")
    )(_ => fail("setup must not start the MCP server"))
    val config = java.nio.file.Files.readString(root.resolve(".mcp.json"))
    assert(!config.contains(root.toString), "JSON config must not bake project root")
    assert(
      config.contains("\"args\": [\"serve\", \".\"]"),
      "JSON config should use cwd root and implicit classpath discovery"
    )
  }

  test("smart_code_duplications exposes code clones across the project") {
    val r = call("smart_code_duplications", ujson.Obj("minSize" -> 15))
    assert(r.obj.contains("groupsCount"))
    assert(r.obj.contains("groups"))
  }

  test("find_overloads reports inherited overloads separately from same-owner overloads") {
    val r = call(
      "find_overloads",
      ujson.Obj(
        "symbol" -> "com/github/mercurievv/scalasemantic/fixtures/OverloadChild#foo()."
      )
    )
    assertEquals(r("name").str, "foo")
    val overloads = jsonArray(r("overloads")).map(_.str).toSet
    assertEquals(overloads, Set("def foo(x: Int): Int", "def foo(x: String): String"))
    val inherited = jsonArray(r("inheritedOverloads")).map(_.str)
    assertEquals(inherited, Vector("def foo(x: Long): Long  (from OverloadParent)"))
  }

  test("find_overloads omits inheritedOverloads when the owner has no ancestor overloads") {
    val r = call(
      "find_overloads",
      ujson.Obj("symbol" -> "com/github/mercurievv/scalasemantic/fixtures/Sample.over().")
    )
    assert(!r.obj.contains("inheritedOverloads"), r.render())
  }

  test("find_symbol resolves a plain name to ranked SemanticDB symbols") {
    val r = call("find_symbol", ujson.Obj("query" -> "Animal"))
    assert(r("count").num > 0)
    val syms = jsonArray(r("symbols")).map(_("symbol").str)
    assert(syms.contains(Animal), syms.toString)
    // exact match ranks first
    assertEquals(r("symbols")(0)("name").str, "Animal")
  }

  test("find_symbol narrows by kind, exact, and pathFilter") {
    val byKind = call("find_symbol", ujson.Obj("query" -> "Animal", "kind" -> "TRAIT"))
    val kinds = byKind("symbols").arr.map(_("kind").str).toSet
    assertEquals(kinds, Set("TRAIT"))

    val exact = call("find_symbol", ujson.Obj("query" -> "Animal", "exact" -> true))
    assert(exact("symbols").arr.forall(_("name").str == "Animal"), exact.render())

    // `VirtualBase` (compat-fixtures' CallGraph.scala) is identically defined in both compat-fixtures
    // version trees.
    val scoped =
      call(
        "find_symbol",
        ujson.Obj("query" -> "VirtualBase", "kind" -> "TRAIT", "pathFilter" -> "*compat*")
      )
    val syms = jsonArray(scoped("symbols")).map(_("symbol").str)
    assertEquals(syms.toList, List("com/github/mercurievv/scalasemantic/compat/VirtualBase#"))
  }

  test("structure ranks types by coupling and rolls up modules (dogfood)") {
    val r = call("structure", ujson.Obj("limit" -> 5))
    val mods = r("modules").arr.map(_("module").str).toSet
    assert(Set("core", "analysis", "mcp").subsetOf(mods), mods.toString)
    // default sort is afferent (fan-in) descending
    val cas = jsonArray(r("symbols")).map(_("ca").num)
    assertEquals(cas.toList, cas.toList.sortBy(-_), "symbols must be ranked by Ca desc")
    // every symbol carries the core metrics, including the Phase-2 layer
    assert(r("symbols").arr.forall(s => s.obj.contains("instability") && s.obj.contains("layer")))
    assert(r("modules").arr.forall(_.obj.contains("layer")), "modules carry a layer")
    // sorting by layer is accepted and ranks descending
    val byLayer = call("structure", ujson.Obj("sort" -> "layer", "limit" -> 5))
    val layers = jsonArray(byLayer("symbols")).map(_("layer").num)
    assertEquals(layers.toList, layers.toList.sortBy(-_), "symbols ranked by layer desc")

    // Phase 3: centrality on every symbol, the module coupling surface, and sort=centrality.
    assert(r("symbols").arr.forall(_.obj.contains("centrality")), "symbols carry centrality")
    assert(r.obj.contains("moduleEdges") && r("moduleEdges").arr.nonEmpty, r.render())
    val byCent = call("structure", ujson.Obj("sort" -> "centrality", "limit" -> 5))
    val cents = jsonArray(byCent("symbols")).map(_("centrality").num)
    assertEquals(cents.toList, cents.toList.sortBy(-_), "symbols ranked by centrality desc")

    // detailed adds the per-dimension breakdown with all dimensions
    val d = call("structure", ujson.Obj("limit" -> 1, "detailed" -> true))
    val dims = d("symbols")(0)("perDimension").obj.keys.toSet
    assertEquals(dims, Set("extends", "typeRef", "memberType", "call", "implicit"), dims.toString)

    // graph is off by default — no mermaid field, output otherwise unchanged
    assert(!r.obj.contains("mermaid"), "mermaid must be absent when graph is omitted")

    // graph=true adds a mermaid graph TD diagram, one line per moduleEdge
    val withGraph = call("structure", ujson.Obj("limit" -> 5, "graph" -> true))
    val mermaid = withGraph("mermaid").str
    assert(mermaid.startsWith("graph TD"), mermaid)
    val edgeCount = withGraph("moduleEdges").arr.size
    val edgeLines = mermaid.linesIterator.toList.tail
    assertEquals(edgeLines.size, edgeCount, mermaid)
    assert(edgeLines.forall(_.contains("-->")), mermaid)
  }

  test("metrics badge: find_symbol and class_hierarchy carry structural metrics on request") {
    // off by default — no badge fields
    val plain = call("find_symbol", ujson.Obj("query" -> "Animal", "kind" -> "TRAIT"))
    assert(plain("symbols").arr.forall(!_.obj.contains("layer")), "no badge without metrics")

    // on → each in-project type result gains layer + centrality
    val badged =
      call("find_symbol", ujson.Obj("query" -> "Animal", "kind" -> "TRAIT", "metrics" -> true))
    val anyBadged =
      badged("symbols").arr.exists(s => s.obj.contains("layer") && s.obj.contains("centrality"))
    assert(anyBadged, badged.render())

    // class_hierarchy badges the queried type
    val h = call("class_hierarchy", ujson.Obj("symbol" -> Animal, "metrics" -> true))
    assert(h.obj.contains("layer") && h.obj.contains("centrality"), h.render())
  }

  test("document_outline maps a file's declarations with clarified signatures") {
    // Derive the fixture file's uri from a definition location (uri:line:col), robust to the path.
    val defLoc = call("find_usages", ujson.Obj("symbol" -> Animal))("definitions").arr.head.str
    val uri = defLoc.split(":").dropRight(2).mkString(":")
    val o = call("document_outline", ujson.Obj("uri" -> uri))

    def flatten(arr: ujson.Value): Seq[ujson.Value] =
      arr.arr.toSeq.flatMap(e => e +: e.obj.get("children").map(flatten).getOrElse(Nil))
    val entries = flatten(o("outline"))
    assert(entries.exists(_("name").str == "Animal"), o.render())
    // a method/value entry renders a resolved signature (the clarified view)
    assert(entries.exists(_.obj.get("signature").exists(_.str.contains(":"))), o.render())
  }

  test("annotated_source surfaces compiler insertions invisible in the source text") {
    // Resolve the fixtures file uri from a definition location, robust to the on-disk path.
    val defLoc = call("find_usages", ujson.Obj("symbol" -> Show))("definitions").arr.head.str
    val uri = defLoc.split(":").dropRight(2).mkString(":")
    val r = call("annotated_source", ujson.Obj("uri" -> uri))
    assert(r("annotationCount").num > 0, r.render())
    val src = r("source").str
    // numbered lines + the annotation marker for the compiler's invisible work
    assert(src.linesIterator.exists(_.contains("⟹")), src)
    // Sample.scala's `listShow` body inserts an inferred type arg and a synthesised using-arg.
    assert(src.contains("[") && src.contains("using"), src)

    // annotationsOnly trims to just the carrying lines (each one keeps the marker).
    val only = call("annotated_source", ujson.Obj("uri" -> uri, "annotationsOnly" -> true))
    assert(only("source").str.linesIterator.forall(_.contains("⟹")), only("source").str)
  }

  test("annotated_source format: plain strips notes, compilable comments them out") {
    val defLoc = call("find_usages", ujson.Obj("symbol" -> Show))("definitions").arr.head.str
    val uri = defLoc.split(":").dropRight(2).mkString(":")

    // plain: the raw file, no annotation markers at all
    val plain = call("annotated_source", ujson.Obj("uri" -> uri, "format" -> "plain"))
    assertEquals(plain("format").str, "plain")
    assert(!plain("source").str.contains("⟹"), "plain must carry no notes")

    // compilable: notes become trailing `// ⟹` comments and the gutter is dropped
    val comp = call("annotated_source", ujson.Obj("uri" -> uri, "format" -> "compilable"))
    val src = comp("source").str
    assert(src.contains("// ⟹"), src)
    // no line-number gutter → the first source line starts at column 0, not padded digits
    assert(!src.linesIterator.next().matches("""\s+\d+\s+\S.*"""), src.linesIterator.next())
  }

  test("annotated_source reports found:false for an unknown uri") {
    val r = call("annotated_source", ujson.Obj("uri" -> "does/not/exist.scala"))
    assertEquals(r("found").bool, false)
  }

  test("rename_plan returns precise, resolved edits (no grep over-match)") {
    val rp = call("rename_plan", ujson.Obj("symbol" -> Animal, "newName" -> "Creature"))
    assertEquals(rp("rename").str, "Animal -> Creature")
    assert(rp("editCount").num > 0, "expected occurrences to rename")
    // edits are uri:line:col-col ranges (definition + references), not whole lines
    assert(rp("edits").arr.forall(_.str.matches(""".+:\d+:\d+-\d+""")), rp.render())
  }

  test("move_plan relocates a symbol and lists references + the FQN change") {
    val mp = call(
      "move_plan",
      ujson.Obj("symbol" -> Animal, "newOwner" -> "com/github/mercurievv/scalasemantic/moved/")
    )
    assertEquals(
      mp("move").str,
      "com.github.mercurievv.scalasemantic.fixtures.Animal -> " +
        "com.github.mercurievv.scalasemantic.moved.Animal"
    )
    assert(mp.obj.contains("definition"), mp.render())
    assert(mp("referenceCount").num > 0, "the move must surface calls/usages")
  }

  test("tools/call invokes the debug-logging hook with the tool name and args") {
    val captured =
      new java.util.concurrent.atomic.AtomicReference[List[(String, ujson.Value)]](Nil)
    val rq = req(
      "tools/call",
      ujson.Obj("name" -> "find_symbol", "arguments" -> ujson.Obj("query" -> "Animal"))
    )
    val _ = Mcp.handle(rq, tools, (n, a) => { val _ = captured.updateAndGet(_ :+ (n -> a)) })
    val c = captured.get
    assertEquals(c.map(_._1), List("find_symbol"))
    assertEquals(c.head._2("query").str, "Animal")
  }

  test("fileLogger writes timestamped tool-call lines to <root>/scala-semantic-mcp.log") {
    val dir = java.nio.file.Files.createTempDirectory("ss-log").nn
    val log = Mcp.fileLogger(dir)
    log("serving from '.'")
    Mcp.logToolCall(log)("find_symbol", ujson.Obj("query" -> "Animal"))
    val file = dir.resolve(s"${Mcp.ServerName}.log")
    val lines = java.nio.file.Files.readAllLines(file).nn
    assertEquals(lines.size, 2)
    assert(lines.get(0).nn.endsWith("serving from '.'"), lines.get(0))
    assert(lines.get(1).nn.contains("""call find_symbol {"query":"Animal"}"""), lines.get(1))
    assert(lines.get(1).nn.startsWith(s"[${Mcp.ServerName}] "), lines.get(1))
  }

  test("resolveClasspathSpec preserves flat classpath file compatibility") {
    val root = java.nio.file.Files.createTempDirectory("ss-flat-cp").nn
    val cpFile = root.resolve("classpath.txt").nn
    java.nio.file.Files.writeString(cpFile, "lib/a.jar\nlib/b.jar")

    val resolved = Mcp.resolveClasspathSpec(cpFile.toString, root)
    assertEquals(resolved.modules.map(_.id).toList, List("flat"))
    assertEquals(
      resolved.merged.toList,
      List(root.resolve("lib/a.jar").normalize().nn, root.resolve("lib/b.jar").normalize().nn)
    )
  }

  test("resolveClasspathSpec parses module-aware JSON and selects the longest baseDir match") {
    val root = java.nio.file.Files.createTempDirectory("ss-json-cp").nn
    val dir = root.resolve(".scala-semantic").nn
    java.nio.file.Files.createDirectories(dir)
    val cpFile = dir.resolve("classpath-sbt.json").nn
    val json = ujson.Obj(
      "schemaVersion" -> 1,
      "buildTool" -> "sbt",
      "modules" -> ujson.Arr(
        ujson.Obj(
          "id" -> "app",
          "baseDir" -> "app",
          "scalaVersion" -> "3.8.4",
          "configuration" -> "Compile",
          "classpath" -> ujson.Arr("app/target/classes", "shared.jar")
        ),
        ujson.Obj(
          "id" -> "app.jvm",
          "baseDir" -> "app/jvm",
          "scalaVersion" -> "3.8.4",
          "configuration" -> "Compile",
          "classpath" -> ujson.Arr("app/jvm/target/classes", "shared.jar")
        )
      )
    )
    java.nio.file.Files.writeString(cpFile, ujson.write(json))

    val resolved = Mcp.resolveClasspathSpec(".scala-semantic/classpath-sbt.json", root)
    assertEquals(resolved.modules.map(_.id).toList, List("app", "app.jvm"))
    assertEquals(
      resolved.moduleFor("app/jvm/src/main/scala/Main.scala", root).map(_.id),
      Some("app.jvm")
    )
    assertEquals(
      resolved.classpathFor("app/jvm/src/main/scala/Main.scala", root).toList,
      List(
        root.resolve("app/jvm/target/classes").normalize().nn,
        root.resolve("shared.jar").normalize().nn
      )
    )
    assertEquals(
      resolved.classpathFor("other/src/main/scala/Main.scala", root).toList,
      List(
        root.resolve("app/target/classes").normalize().nn,
        root.resolve("shared.jar").normalize().nn,
        root.resolve("app/jvm/target/classes").normalize().nn
      )
    )
    assertEquals(
      resolved.moduleFor("other/src/main/scala/Main.scala", root).map(_.id),
      Some("merged")
    )
  }

  test("resolveClasspath discovers root metadata when no classpath argument is supplied") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-root-cp").nn
    val dir = root.resolve(".scala-semantic").nn
    java.nio.file.Files.createDirectories(dir)
    val cpFile = dir.resolve("classpath-sbt.json").nn
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "sbt",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "root",
              "baseDir" -> ".",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr("target/classes")
            )
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(cpFile))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toList, List("root"))
    assertEquals(resolved.merged.toList, List(root.resolve("target/classes").normalize().nn))
  }

  test("resolveClasspath follows module metadata from root to child source directory") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-module-src-cp").nn
    val rootMetadata = root.resolve(".scala-semantic").nn
    val child = root.resolve("modules").resolve("app").nn
    java.nio.file.Files.createDirectories(rootMetadata)
    java.nio.file.Files.createDirectories(child.resolve(".scala-semantic"))
    val rootCp = rootMetadata.resolve("classpath-sbt.json").nn
    val childCp = child.resolve(".scala-semantic").resolve("classpath-mill.json").nn
    java.nio.file.Files.writeString(
      rootMetadata.resolve("modules-mill.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "mill",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "name" -> "app",
              "path_from_root" -> "modules/app",
              "path_to_out_dir" -> "out/app"
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      rootCp,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "sbt",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "root",
              "baseDir" -> ".",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr("target/classes")
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      childCp,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "mill",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "app",
              "baseDir" -> "modules/app",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr("out/app/classes")
            )
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(rootCp, childCp))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toList, List("root", "app"))
    assertEquals(
      resolved.classpathFor("modules/app/src/main/scala/Main.scala", root).toList,
      List(root.resolve("out/app/classes").normalize().nn)
    )
  }

  test("resolveClasspath follows module metadata to output directories") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-module-out-cp").nn
    val rootMetadata = root.resolve(".scala-semantic").nn
    val out = root.resolve("out").resolve("modules").resolve("app").nn
    java.nio.file.Files.createDirectories(rootMetadata)
    java.nio.file.Files.createDirectories(out.resolve(".scala-semantic"))
    val cpFile = out.resolve(".scala-semantic").resolve("classpath.json").nn
    java.nio.file.Files.writeString(
      rootMetadata.resolve("modules-mill.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "mill",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "name" -> "app",
              "path_from_root" -> "modules/app",
              "path_to_out_dir" -> "out/modules/app"
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "custom",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "app",
              "baseDir" -> "modules/app",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr("out/modules/app/classes")
            )
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(cpFile))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toList, List("app"))
  }

  test("resolveClasspath merges per-tool module metadata lists") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-module-tools-cp").nn
    val rootMetadata = root.resolve(".scala-semantic").nn
    val app = root.resolve("modules").resolve("app").nn
    val lib = root.resolve("modules").resolve("lib").nn
    java.nio.file.Files.createDirectories(rootMetadata)
    java.nio.file.Files.createDirectories(app.resolve(".scala-semantic"))
    java.nio.file.Files.createDirectories(lib.resolve(".scala-semantic"))
    val appCp = app.resolve(".scala-semantic").resolve("classpath-mill.json").nn
    val libCp = lib.resolve(".scala-semantic").resolve("classpath-sbt.json").nn
    java.nio.file.Files.writeString(
      rootMetadata.resolve("modules-mill.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "mill",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "name" -> "app",
              "path_from_root" -> "modules/app",
              "path_to_out_dir" -> "out/app"
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      rootMetadata.resolve("modules-sbt.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "sbt",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "name" -> "lib",
              "path_from_root" -> "modules/lib",
              "path_to_out_dir" -> "target/lib"
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      appCp,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "mill",
          "modules" -> ujson.Arr(
            ujson.Obj("id" -> "app", "baseDir" -> "modules/app", "classpath" -> ujson.Arr("a"))
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      libCp,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "sbt",
          "modules" -> ujson.Arr(
            ujson.Obj("id" -> "lib", "baseDir" -> "modules/lib", "classpath" -> ujson.Arr("b"))
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toSet, Set(appCp, libCp))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toSet, Set("app", "lib"))
  }

  test("resolveClasspath allows module metadata paths outside the root") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-external-root-cp").nn
    val parent = root.getParent.nn
    val external =
      java.nio.file.Files.createTempDirectory(parent, "ss-discover-external-module-cp").nn
    val rootMetadata = root.resolve(".scala-semantic").nn
    java.nio.file.Files.createDirectories(rootMetadata)
    java.nio.file.Files.createDirectories(external.resolve(".scala-semantic"))
    val relativeExternal = root.relativize(external).toString
    val cpFile = external.resolve(".scala-semantic").resolve("classpath.json").nn
    java.nio.file.Files.writeString(
      rootMetadata.resolve("modules.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "modules" -> ujson.Arr(
            ujson.Obj(
              "name" -> "external",
              "path_from_root" -> relativeExternal,
              "path_to_out_dir" -> "missing-out"
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "external",
              "baseDir" -> relativeExternal,
              "classpath" -> ujson.Arr(s"$relativeExternal/classes")
            )
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(cpFile))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toList, List("external"))
  }

  test("resolveClasspath terminates module metadata cycles and deduplicates classpath files") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-module-cycle-cp").nn
    val rootMetadata = root.resolve(".scala-semantic").nn
    val child = root.resolve("modules").resolve("app").nn
    java.nio.file.Files.createDirectories(rootMetadata)
    java.nio.file.Files.createDirectories(child.resolve(".scala-semantic"))
    val cpFile = child.resolve(".scala-semantic").resolve("classpath.json").nn
    java.nio.file.Files.writeString(
      rootMetadata.resolve("modules.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "modules" -> ujson.Arr(
            ujson.Obj(
              "name" -> "app-a",
              "path_from_root" -> "modules/app",
              "path_to_out_dir" -> "modules/app"
            ),
            ujson.Obj(
              "name" -> "app-b",
              "pathFromRoot" -> "modules/app",
              "pathToOutDir" -> "modules/app"
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      child.resolve(".scala-semantic").resolve("modules.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "modules" -> ujson.Arr(
            ujson.Obj("name" -> "root", "path_from_root" -> ".", "path_to_out_dir" -> ".")
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "modules" -> ujson.Arr(
            ujson.Obj("id" -> "app", "baseDir" -> "modules/app", "classpath" -> ujson.Arr("a"))
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(cpFile))
  }

  test("resolveClasspath discovers submodule metadata without entering hidden directories") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-sub-cp").nn
    val sub = root.resolve("modules").resolve("app").nn
    val hidden = root.resolve(".hidden").resolve("app").nn
    java.nio.file.Files.createDirectories(sub.resolve(".scala-semantic"))
    java.nio.file.Files.createDirectories(hidden.resolve(".scala-semantic"))
    val cpFile = sub.resolve(".scala-semantic").resolve("classpath-mill.json").nn
    val hiddenFile = hidden.resolve(".scala-semantic").resolve("classpath-sbt.json").nn
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "mill",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "app",
              "baseDir" -> "modules/app",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr("modules/app/out/classes")
            )
          )
        )
      )
    )
    java.nio.file.Files.writeString(
      hiddenFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "sbt",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "hidden",
              "baseDir" -> ".hidden/app",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr(".hidden/app/target/classes")
            )
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(cpFile))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toList, List("app"))
    assertEquals(
      resolved.classpathFor("modules/app/src/main/scala/Main.scala", root).toList,
      List(root.resolve("modules/app/out/classes").normalize().nn)
    )
  }

  test("resolveClasspath discovers metadata inside submodule output directories") {
    val root = java.nio.file.Files.createTempDirectory("ss-discover-out-cp").nn
    val out = root.resolve("out").resolve("app").resolve("compile.dest").nn
    java.nio.file.Files.createDirectories(out.resolve(".scala-semantic"))
    val cpFile = out.resolve(".scala-semantic").resolve("classpath.json").nn
    java.nio.file.Files.writeString(
      cpFile,
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "buildTool" -> "custom",
          "modules" -> ujson.Arr(
            ujson.Obj(
              "id" -> "app",
              "baseDir" -> "modules/app",
              "scalaVersion" -> "3.8.4",
              "configuration" -> "Compile",
              "classpath" -> ujson.Arr("out/app/compile.dest/classes")
            )
          )
        )
      )
    )

    val files = Mcp.discoverClasspathMetadata(root)
    assertEquals(files.toList, List(cpFile))
    val resolved = Mcp.resolveClasspath(None, root).getOrElse(fail("classpath not discovered"))
    assertEquals(resolved.modules.map(_.id).toList, List("app"))
    assertEquals(
      resolved.classpathFor("modules/app/src/main/scala/Main.scala", root).toList,
      List(root.resolve("out/app/compile.dest/classes").normalize().nn)
    )
  }

  test("resolveClasspath ignores missing or invalid metadata files") {
    val root = java.nio.file.Files.createTempDirectory("ss-bad-cp").nn
    assertEquals(Mcp.resolveClasspath(Some(root.resolve("missing.txt").toString), root), None)

    val invalid = root.resolve("classpath-sbt.json").nn
    java.nio.file.Files.writeString(invalid, "{not-json")
    assertEquals(Mcp.resolveClasspath(Some(invalid.toString), root), None)
  }

  test("notifications get no response") {
    val n = ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized")
    assertEquals(Mcp.handle(n, tools), None)
  }

  test("unknown tool yields a JSON-RPC error") {
    val r = Mcp
      .handle(req("tools/call", ujson.Obj("name" -> "nope", "arguments" -> ujson.Obj())), tools)
      .get
    assert(r.obj.contains("error"), r.render())
  }

  test("invalid tool inputs are rejected at the MCP boundary") {
    val badSymbol = callResponse("find_usages", ujson.Obj("symbol" -> "not a symbol"))
      .getOrElse(fail("no response"))
    assertEquals(badSymbol("result")("isError").bool, true)
    assert(badSymbol("result")("content")(0)("text").str.contains("invalid SemanticDB symbol"))

    val badLimit =
      callResponse("find_symbol", ujson.Obj("query" -> "Animal", "limit" -> 0))
        .getOrElse(fail("no response"))
    assertEquals(badLimit("result")("isError").bool, true)
    assert(badLimit("result")("content")(0)("text").str.contains("limit must be > 0"))

    val badRename =
      callResponse("rename_plan", ujson.Obj("symbol" -> Animal, "newName" -> "class"))
        .getOrElse(fail("no response"))
    assertEquals(badRename("result")("isError").bool, true)
    assert(badRename("result")("content")(0)("text").str.contains("invalid Scala identifier"))

    val badRange = callResponse(
      "extract_method_plan",
      ujson.Obj(
        "uri" -> "Sample.scala",
        "startLine" -> 5,
        "startCharacter" -> 0,
        "endLine" -> 4,
        "endCharacter" -> 0
      )
    ).getOrElse(fail("no response"))
    assertEquals(badRange("result")("isError").bool, true)
    assert(badRange("result")("content")(0)("text").str.contains("range end"))
  }

  test("find_usages returns a count and paged references") {
    val r = call("find_usages", ujson.Obj("symbol" -> Animal, "limit" -> 1))
    assert(r("referenceCount").num > 0)
    assert(r("references").arr.nonEmpty)
    // compact location form uri:line:col
    assert(r("references")(0).str.count(_ == ':') >= 2, r("references")(0).str)
  }

  test("find_usages include selects sections; referenceCount always stays") {
    val full = call("find_usages", ujson.Obj("symbol" -> Animal))
    assert(full.obj.contains("definitions") && full.obj.contains("references"), full.render())

    val countOnly = call("find_usages", ujson.Obj("symbol" -> Animal, "include" -> ujson.Arr()))
    assert(!countOnly.obj.contains("definitions"), countOnly.render())
    assert(!countOnly.obj.contains("references"), countOnly.render())
    assert(countOnly("referenceCount").num > 0, "count must survive any include")

    val refsOnly =
      call("find_usages", ujson.Obj("symbol" -> Animal, "include" -> ujson.Arr("references")))
    assert(!refsOnly.obj.contains("definitions"), refsOnly.render())
    assert(refsOnly("references").arr.nonEmpty, refsOnly.render())
  }

  test("find_usages pathFilter scopes references by document-uri glob") {
    val compat = "com/github/mercurievv/scalasemantic/compat/VirtualBase#"
    val all = call("find_usages", ujson.Obj("symbol" -> compat))
    val scoped = call("find_usages", ujson.Obj("symbol" -> compat, "pathFilter" -> "*scala-3*"))
    assert(scoped("referenceCount").num < all("referenceCount").num, "filter must drop refs")
    assert(scoped("references").arr.forall(_.str.contains("scala-3")), scoped.render())
  }

  test("find_usages contextLines omitted keeps *WithContext fields empty (back-compat)") {
    val r = call("find_usages", ujson.Obj("symbol" -> Animal))
    assert(!r.obj.contains("definitionsWithContext"), r.render())
    assert(!r.obj.contains("referencesWithContext"), r.render())
  }

  test("find_usages contextLines returns surrounding source lines per hit") {
    val r = call("find_usages", ujson.Obj("symbol" -> Animal, "contextLines" -> 2))
    assert(r("referencesWithContext").arr.nonEmpty, r.render())
    val hit = r("referencesWithContext").arr.head
    assert(hit.obj.contains("location"), hit.render())
    assert(hit("context").arr.size > 1, hit.render())
  }

  test("document_outline without filters is unchanged and carries no new keys (back-compat)") {
    val fixture =
      "analysis/src/test/scala/com/github/mercurievv/scalasemantic/fixtures/Sample.scala"
    val r = call("document_outline", ujson.Obj("uri" -> fixture))
    assert(!r.obj.contains("filtered"), r.render())
    assert(!r.obj.contains("liveSource"), r.render())
    assert(jsonArray(r("outline")).exists(_("name").str == "Animal"), r.render())
  }

  test("document_outline query narrows to the match and its enclosing scope") {
    val fixture =
      "analysis/src/test/scala/com/github/mercurievv/scalasemantic/fixtures/Sample.scala"
    val r = call("document_outline", ujson.Obj("uri" -> fixture, "query" -> "swim"))
    assertEquals(r("filtered").bool, true)
    val names = jsonArray(r("outline")).map(_("name").str)
    assert(names.nonEmpty && names.size < 5, s"expected a narrow result, got $names")
    assert(!names.contains("Animal"), s"unrelated types must be dropped, got $names")
  }

  test("document_outline maxDepth alone bounds nesting without dropping top-level types") {
    val fixture =
      "analysis/src/test/scala/com/github/mercurievv/scalasemantic/fixtures/Sample.scala"
    val r = call("document_outline", ujson.Obj("uri" -> fixture, "maxDepth" -> 1))
    val entries = jsonArray(r("outline"))
    assert(entries.exists(_("name").str == "Animal"), r.render())
    assert(entries.forall(!_.obj.contains("children")), "no nesting survives maxDepth=1")
  }

  test("find_usages omits the related section for a type that is not case-like (back-compat)") {
    val r = call("find_usages", ujson.Obj("symbol" -> Animal))
    assert(!r.obj.contains("related"), r.render())
  }

  test("find_usages returns labelled related groups for a case class") {
    val order = "com/github/mercurievv/scalasemantic/fixtures/Order#"
    val r = call("find_usages", ujson.Obj("symbol" -> order))
    val groups = jsonArray(r("related"))
    assert(groups.exists(_("kind").str == "companion"), r.render())
    val companion = groups.find(_("kind").str == "companion").get
    assert(companion("locations").arr.nonEmpty, companion.render())
    assert(companion("symbol").str.endsWith("/Order."), companion.render())
  }

  test("find_usages related narrows to the requested kinds, and [] drops the section") {
    val order = "com/github/mercurievv/scalasemantic/fixtures/Order#"
    val only = call("find_usages", ujson.Obj("symbol" -> order, "related" -> ujson.Arr("copy")))
    assert(jsonArray(only("related")).forall(_("kind").str == "copy"), only.render())
    val none = call("find_usages", ujson.Obj("symbol" -> order, "related" -> ujson.Arr()))
    assert(!none.obj.contains("related"), none.render())
  }

  test("search_text finds a known literal string in this repo's own sources") {
    val r = call("search_text", ujson.Obj("query" -> "Phase 4: MCP protocol"))
    assert(r("count").num > 0, r.render())
    val hits = jsonArray(r("hits"))
    assert(hits.exists(h => h("uri").str.endsWith("McpSuite.scala")), r.render())
    assert(
      hits.forall(h => h.obj.contains("uri") && h.obj.contains("line") && h.obj.contains("text"))
    )
  }

  test("search_text respects limit, pathFilter, and rejects an invalid regex") {
    val limited = call("search_text", ujson.Obj("query" -> "def ", "limit" -> 3))
    assert(limited("hits").arr.size <= 3, limited.render())

    val scoped = call("search_text", ujson.Obj("query" -> "McpSuite", "pathFilter" -> "*McpSuite*"))
    assert(scoped("hits").arr.forall(_("uri").str.contains("McpSuite")), scoped.render())

    val badRegex =
      callResponse("search_text", ujson.Obj("query" -> "(unclosed", "regex" -> true))
        .getOrElse(fail("no response"))
    assertEquals(badRegex("result")("isError").bool, true)
  }

  test("batch_rename_plan combines edits from unrelated symbols with no conflicts") {
    val a = "com/github/mercurievv/scalasemantic/fixtures/Show#"
    val b = "com/github/mercurievv/scalasemantic/fixtures/Robot#"
    val singleA = call("rename_plan", ujson.Obj("symbol" -> a, "newName" -> "Displayable"))
    val singleB = call("rename_plan", ujson.Obj("symbol" -> b, "newName" -> "Automaton"))
    val batch = call(
      "batch_rename_plan",
      ujson.Obj(
        "renames" -> ujson.Arr(
          ujson.Obj("symbol" -> a, "newName" -> "Displayable"),
          ujson.Obj("symbol" -> b, "newName" -> "Automaton")
        )
      )
    )
    assertEquals(
      batch("combinedEditCount").num,
      singleA("editCount").num + singleB("editCount").num
    )
    assertEquals(batch("conflictCount").num, 0.0)
    assert(!batch.obj.contains("conflicts"), batch.render())
  }

  test(
    "batch_rename_plan reports conflicts for overlapping edits and excludes them from combinedEdits"
  ) {
    val batch = call(
      "batch_rename_plan",
      ujson.Obj(
        "renames" -> ujson.Arr(
          ujson.Obj("symbol" -> Animal, "newName" -> "Creature"),
          ujson.Obj("symbol" -> Animal, "newName" -> "Beast")
        )
      )
    )
    assert(batch("conflictCount").num > 0, batch.render())
    assert(batch("conflicts").arr.nonEmpty, batch.render())
    val single = call("rename_plan", ujson.Obj("symbol" -> Animal, "newName" -> "Creature"))
    val singleEdits = single("edits").arr.map(_.str).toSet
    val combinedEdits = batch("combinedEdits").arr.map(_.str).toSet
    assert(
      singleEdits.intersect(combinedEdits).isEmpty,
      "edits involved in a conflict must not appear in combinedEdits"
    )
  }

  test("method_signature is lean by default and structured only when detailed") {
    val lean = call("method_signature", ujson.Obj("symbol" -> Render))
    assertEquals(lean("signature").str, "def render[A](a: A)(implicit sh: Show[A]): String")
    assert(!lean.obj.contains("parameterLists"), "lean result must not expand parameter lists")

    val full = call("method_signature", ujson.Obj("symbol" -> Render, "detailed" -> true))
    assert(full.obj.contains("parameterLists"), full.render())
    assertEquals(full("parameterLists").arr.last("implicit").bool, true)
  }

  test("class_hierarchy reports known subtypes as display names by default") {
    val r = call("class_hierarchy", ujson.Obj("symbol" -> Animal))
    assertEquals(r("knownSubtypes").arr.map(_.str).toList, List("Dog", "Fish"))
  }

  test("class_hierarchy include selects sections and pathFilter scopes subtypes") {
    val full = call("class_hierarchy", ujson.Obj("symbol" -> Animal))
    assert(full.obj.contains("knownSubtypes"), full.render())

    val only =
      call(
        "class_hierarchy",
        ujson.Obj("symbol" -> Animal, "include" -> ujson.Arr("knownSubtypes"))
      )
    assert(!only.obj.contains("parents") && !only.obj.contains("linearization"), only.render())
    assertEquals(only("knownSubtypes").arr.map(_.str).toList, List("Dog", "Fish"))

    // Dog/Fish live under fixtures → a compat glob empties the list, dropping the section.
    val scoped = call("class_hierarchy", ujson.Obj("symbol" -> Animal, "pathFilter" -> "*compat*"))
    assert(!scoped.obj.contains("knownSubtypes"), scoped.render())
  }

  test("members include selects sections and pathFilter scopes members") {
    val full = call("members", ujson.Obj("symbol" -> Robot))
    assert(full.obj.contains("inherited"), full.render())

    val declOnly =
      call("members", ujson.Obj("symbol" -> Robot, "include" -> ujson.Arr("declared")))
    assert(!declOnly.obj.contains("inherited"), declOnly.render())

    val scoped = call("members", ujson.Obj("symbol" -> Robot, "pathFilter" -> "*compat*"))
    assert(!scoped.obj.contains("declared") && !scoped.obj.contains("inherited"), scoped.render())
  }

  // --- Task M: class-accepting tools also accept a file path -----------------

  private val SemanticIndexFile =
    "core/src/main/scala/com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex.scala"
  private val SemanticIndexClass =
    "com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#"
  private val SampleFile =
    "analysis/src/test/scala/com/github/mercurievv/scalasemantic/fixtures/Sample.scala"

  test("members accepts a file path: a single-type file resolves to that type's members") {
    val bySymbol = call("members", ujson.Obj("symbol" -> SemanticIndexClass))
    val byFile = call("members", ujson.Obj("symbol" -> SemanticIndexFile))
    assertEquals(byFile("symbol").str, bySymbol("symbol").str)
    assertEquals(byFile("name").str, bySymbol("name").str)
    assertEquals(byFile("declared").arr.map(_.str).toSet, bySymbol("declared").arr.map(_.str).toSet)
  }

  test(
    "class_hierarchy accepts a file path: a single-type file resolves to that type's hierarchy"
  ) {
    val bySymbol = call("class_hierarchy", ujson.Obj("symbol" -> SemanticIndexClass))
    val byFile = call("class_hierarchy", ujson.Obj("symbol" -> SemanticIndexFile))
    assertEquals(byFile("symbol").str, bySymbol("symbol").str)
    assertEquals(byFile("name").str, bySymbol("name").str)
  }

  test("members/class_hierarchy on a multi-type file path return disambiguation candidates") {
    val members = call("members", ujson.Obj("symbol" -> SampleFile))
    assert(members.obj.contains("candidates"), members.render())
    assert(members("candidates").arr.map(_.str).contains(Animal), members.render())
    assert(members.obj.contains("note"), members.render())

    val hierarchy = call("class_hierarchy", ujson.Obj("symbol" -> SampleFile))
    assert(hierarchy.obj.contains("candidates"), hierarchy.render())
    assert(hierarchy("candidates").arr.map(_.str).contains(Animal), hierarchy.render())
  }

  test(
    "method_signature/find_overloads accept a file path; a multi-method file returns candidates"
  ) {
    val sig = call("method_signature", ujson.Obj("symbol" -> SampleFile))
    assert(sig.obj.contains("candidates"), sig.render())

    val overloads = call("find_overloads", ujson.Obj("symbol" -> SampleFile))
    assert(overloads.obj.contains("candidates"), overloads.render())
  }

  test("class_hierarchy/members error clearly on a value that is neither a symbol nor a file") {
    intercept[Exception](call("members", ujson.Obj("symbol" -> "not/a/real/path.scala")))
    intercept[Exception](call("class_hierarchy", ujson.Obj("symbol" -> "totally bogus input")))
  }

  test("resolve_implicits lists candidate givens for a type") {
    val r = call("resolve_implicits", ujson.Obj("type" -> Show))
    val types = r("candidates").arr.map(_("type").str).toSet
    assertEquals(types, Set("Show[Int]", "Show[List[A]]"))
  }

  test("call_path reports reachability and the method chain") {
    val a = "com/github/mercurievv/scalasemantic/fixtures/Calls.a()."
    val c = "com/github/mercurievv/scalasemantic/fixtures/Calls.c()."
    val r = call("call_path", ujson.Obj("from" -> a, "to" -> c))
    assertEquals(r("reachable").bool, true)
    assertEquals(r("path").arr.map(_.str).toList, List("a", "b", "c"))
  }

  test("process maps a request stream to responses, skipping blanks and notifications") {
    val in = Iterator(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
      "",
      """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
    )
    val out = Mcp.process(in, tools).toList
    // 4 lines in (one blank, one notification) → 2 responses out, with matching ids
    assertEquals(out.size, 2)
    assertEquals(ujson.read(out(0))("id").num, 1.0)
    assertEquals(ujson.read(out(1))("result")("tools").arr.size, 27)
  }
