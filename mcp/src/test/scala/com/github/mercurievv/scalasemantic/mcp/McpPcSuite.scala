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

  private def req(
      method: String,
      params: ujson.Value,
      id: ujson.Value = ujson.Num(1)
  ): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)

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
