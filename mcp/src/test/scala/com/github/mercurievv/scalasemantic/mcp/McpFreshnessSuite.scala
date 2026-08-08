package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import scala.meta.internal.semanticdb as s

/** Three ways the server used to answer without being able to say its answer was unreliable:
  *
  *   - a recompile left every tool serving the previous index, indefinitely and silently, because
  *     the staleness check ran only inside `set_workspace_root` (#290);
  *   - a source file the build never compiled produced `count: 0`, identical to "does not exist"
  *     (#291);
  *   - a fatal throwable from one tool call unwound the stdio loop and killed the process, so the
  *     client saw only a closed transport (#292).
  */
// Throw: two tests deliberately raise a fatal error from a tool — that is the condition under test.
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class McpFreshnessSuite extends munit.FunSuite:

  private def doc(uri: String, name: String, symbol: String): s.TextDocument =
    s.TextDocument(
      uri = uri,
      symbols = Seq(
        s.SymbolInformation(
          symbol = symbol,
          kind = s.SymbolInformation.Kind.CLASS,
          displayName = name
        )
      )
    )

  private def writeSemanticdb(root: Path, name: String, docs: s.TextDocument*): Unit =
    val dir = root.resolve("META-INF").nn.resolve("semanticdb").nn
    Files.createDirectories(dir)
    val _ = Files.write(
      dir.resolve(s"$name.semanticdb").nn,
      s.TextDocuments(documents = docs.toSeq).toByteArray
    )

  private def writeSource(root: Path, rel: String): Unit =
    val p = root.resolve(rel).nn
    Option(p.getParent).foreach(Files.createDirectories(_))
    val _ = Files.write(p, "class X\n".getBytes("UTF-8"))

  /** Run `body` with the server's global state isolated and restored afterwards. */
  private def withServerState[A](root: Path)(body: AtomicInteger => A): A =
    val savedState = Mcp.state.get()
    val savedFactory = Mcp.stateFactory.get()
    val builds = AtomicInteger(0)
    try
      Mcp.stateCache.remove(root)
      Mcp.stateFactory.set { r =>
        val _ = builds.incrementAndGet()
        savedFactory(r)
      }
      val initial = Mcp.stateFactory.get()(root)
      Mcp.activateState(initial)
      val _ = Mcp.stateCache.put(root, initial)
      body(builds)
    finally
      Mcp.stateCache.remove(root)
      Mcp.stateFactory.set(savedFactory)
      Mcp.state.set(savedState)

  /** A tool that fails the way `scala.util.Try` never caught: fatally. */
  private val boom = Tool(
    "boom",
    "throws a fatal error",
    ujson.Obj("type" -> "object", "properties" -> ujson.Obj()),
    _ => throw new StackOverflowError("simulated deep recursion") // scalafix:ok DisableSyntax.throw
  )

  private def call(name: String, args: ujson.Value): ujson.Value =
    val req = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "tools/call",
      "params" -> ujson.Obj("name" -> name, "arguments" -> args)
    )
    val resp = Mcp.handle(req, Nil).getOrElse(fail("no response"))
    ujson.read(resp("result")("content")(0)("text").str)

  test("a recompile is picked up without any set_workspace_root or refresh_workspace call") {
    val root = Files.createTempDirectory("mcp-stale").nn
    writeSemanticdb(root, "Alpha.scala", doc("Alpha.scala", "Alpha", "demo/Alpha#"))

    withServerState(root) { builds =>
      assertEquals(call("find_symbol", ujson.Obj("query" -> "Beta"))("count").num, 0.0)

      // The "recompile": a new semanticdb file appears under the same root.
      writeSemanticdb(root, "Beta.scala", doc("Beta.scala", "Beta", "demo/Beta#"))

      val after = call("find_symbol", ujson.Obj("query" -> "Beta"))
      assertEquals(after("count").num, 1.0, "the freshly compiled symbol must be visible")
      assertEquals(after("symbols")(0)("symbol").str, "demo/Beta#")
      assertEquals(builds.get(), 2, "exactly one rebuild, triggered by the changed fingerprint")
    }
  }

  test("an unchanged semanticdb tree is never rebuilt") {
    val root = Files.createTempDirectory("mcp-fresh").nn
    writeSemanticdb(root, "Alpha.scala", doc("Alpha.scala", "Alpha", "demo/Alpha#"))

    withServerState(root) { builds =>
      (1 to 3).foreach { _ =>
        val _ = call("find_symbol", ujson.Obj("query" -> "Alpha"))
      }
      assertEquals(builds.get(), 1, "the initial build only")
    }
  }

  test("an empty result over a partially indexed project says so") {
    val root = Files.createTempDirectory("mcp-coverage").nn
    writeSemanticdb(root, "Alpha.scala", doc("Alpha.scala", "Alpha", "demo/Alpha#"))
    writeSource(root, "Alpha.scala")
    writeSource(root, "Alpha.test.scala")

    withServerState(root) { _ =>
      val res = call("find_symbol", ujson.Obj("query" -> "Nothingness"))
      assertEquals(res("count").num, 0.0)
      val hint = res.obj.get("coverageHint").map(_.str).getOrElse("")
      assert(hint.contains("1 of 2"), hint)
      assert(hint.contains("--test"), hint)

      // A non-empty answer carries no hint — the point is to flag ambiguous zeros, not to nag.
      val found = call("find_symbol", ujson.Obj("query" -> "Alpha"))
      assertEquals(found("count").num, 1.0)
      assert(found.obj.get("coverageHint").isEmpty, found.render())

      val cov = call("get_workspace_root", ujson.Obj())("coverage")
      assertEquals(cov("sources").num, 2.0)
      assertEquals(cov("indexed").num, 1.0)
      assertEquals(cov("unindexed")(0).str, "Alpha.test.scala")
    }
  }

  test("a fully indexed project attaches no coverage hint to an empty result") {
    val root = Files.createTempDirectory("mcp-coverage-full").nn
    writeSemanticdb(root, "Alpha.scala", doc("Alpha.scala", "Alpha", "demo/Alpha#"))
    writeSource(root, "Alpha.scala")

    withServerState(root) { _ =>
      val res = call("find_symbol", ujson.Obj("query" -> "Nothingness"))
      assertEquals(res("count").num, 0.0)
      assert(res.obj.get("coverageHint").isEmpty, res.render())
    }
  }

  // StackOverflowError is fatal, so `scala.util.Try` never caught it: a deep graph walk killed the
  // whole server rather than the request.
  test("a fatal throwable from a tool becomes an isError response, not a dead server") {
    val savedState = Mcp.state.get()
    try
      Mcp.state.set(None)
      val req = ujson.Obj(
        "jsonrpc" -> "2.0",
        "id" -> 7,
        "method" -> "tools/call",
        "params" -> ujson.Obj("name" -> "boom", "arguments" -> ujson.Obj())
      )
      val resp = Mcp.handle(req, List(boom)).getOrElse(fail("no response"))
      assertEquals(resp("result")("isError").bool, true)
      assert(
        resp("result")("content")(0)("text").str.contains("simulated deep recursion"),
        resp.render()
      )
    finally Mcp.state.set(savedState)
  }

  test("a request stream survives a fatal tool failure and keeps answering") {
    val savedState = Mcp.state.get()
    try
      Mcp.state.set(None)
      val in = Iterator(
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom","arguments":{}}}""",
        """{"jsonrpc":"2.0","id":2,"method":"ping","params":{}}"""
      )
      val out = Mcp.process(in, List(boom)).toList
      assertEquals(out.size, 2, "the loop must keep running after the fatal call")
      assertEquals(ujson.read(out(1))("id").num, 2.0)
    finally Mcp.state.set(savedState)
  }

  // A surviving loop is not enough: a request that carries an `id` and gets no reply leaves the
  // client blocked forever, which it reports as a closed or dead transport — the symptom of #292
  // finding 3. The failure has to come back as an answer.
  test("a request whose dispatch throws is still answered, with its own id") {
    val savedState = Mcp.state.get()
    val savedFactory = Mcp.stateFactory.get()
    val root = Files.createTempDirectory("mcp-dispatch-boom").nn
    try
      writeSemanticdb(root, "Alpha.scala", doc("Alpha.scala", "Alpha", "demo/Alpha#"))
      val initial = savedFactory(root)
      Mcp.activateState(initial)
      val _ = Mcp.stateCache.put(root, initial)
      // Move the fingerprint so the next request rebuilds, then make that rebuild fail fatally.
      // This is the OOM-on-a-large-index path: it happens inside `ensureFresh`, before any tool
      // runs, so `runToolGuarded` never sees it.
      writeSemanticdb(root, "Beta.scala", doc("Beta.scala", "Beta", "demo/Beta#"))
      Mcp.stateFactory.set(_ =>
        throw new OutOfMemoryError("simulated index rebuild") // scalafix:ok DisableSyntax.throw
      )
      val in = Iterator(
        """{"jsonrpc":"2.0","id":11,"method":"tools/list","params":{}}""",
        """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
        """not json at all"""
      )
      val out = Mcp.process(in, Nil).toList.map(ujson.read(_))
      assertEquals(out.size, 1, "only the request with an id is answerable")
      assertEquals(out.head("id").num, 11.0)
      assertEquals(out.head("error")("code").num, -32603.0)
      assert(out.head("error")("message").str.contains("OutOfMemoryError"), out.head.render())
    finally
      Mcp.stateCache.remove(root)
      Mcp.stateFactory.set(savedFactory)
      Mcp.state.set(savedState)
  }

  test("requestId echoes a usable id and refuses one that is absent or null") {
    assertEquals(Mcp.requestId(ujson.Obj("id" -> 4)).map(_.num), Some(4.0))
    assertEquals(Mcp.requestId(ujson.Obj("id" -> "abc")).map(_.str), Some("abc"))
    assertEquals(Mcp.requestId(ujson.Obj("method" -> "ping")), None, "a notification has no id")
    assertEquals(Mcp.requestId(ujson.Obj("id" -> ujson.Null)), None, "null is not an id")
    assertEquals(Mcp.requestId(ujson.Str("not an object")), None, "never throws on a non-object")
  }
