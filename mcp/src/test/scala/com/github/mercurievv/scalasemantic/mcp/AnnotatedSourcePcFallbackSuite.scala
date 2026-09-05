package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** `annotated_source`'s presentation-compiler fallback: when a file has no compiled SemanticDB
  * entry -- a syntax error, never built, or excluded from the classpath -- the compiled index alone
  * cannot answer, but the presentation compiler can still best-effort-typecheck the buffer alone.
  * The tool falls back to that instead of refusing to show the file.
  */
class AnnotatedSourcePcFallbackSuite extends munit.FunSuite:

  private val Uri = "Widget.scala"
  // A file whose tail does not typecheck; the PC must still describe `area` above it.
  private val Source =
    """package demo
      |
      |class Widget:
      |  def area(w: Int): Int = w * 2
      |
      |val broken: Int = "oops"
      |""".stripMargin

  test("a file with no compiled SemanticDB entry falls back to the presentation compiler"):
    val root = java.nio.file.Files.createTempDirectory("ss-pc-fallback").nn
    val file = root.resolve(Uri).nn
    java.nio.file.Files.writeString(file, Source)

    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val tools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty), Some(backend)), root)
      val res = tools
        .find(_.name == "annotated_source")
        .getOrElse(fail("no annotated_source"))
        .run(ujson.Obj("uri" -> Uri))

      assertEquals(res.obj.contains("found"), false, res.render())
      assertEquals(res("pcFallback").bool, true)
      assert(res.obj.contains("pcFallbackHint"), res.render())
      assert(res("source").str.contains("def area(w: Int): Int"), res("source").str)
      // No compiled digest exists at all under a PC fallback -- the field is skipped, not `false`.
      assertEquals(res.obj.contains("staleIndex"), false)
    }

  test("a file with a compiled index entry does not take the PC fallback"):
    val root = java.nio.file.Files.createTempDirectory("ss-pc-fallback-compiled").nn
    val uri = "Foo.scala"
    val source = "object Foo:\n  def bar = 1\n"
    java.nio.file.Files.writeString(root.resolve(uri), source)
    val md5 = McpToolsSupport.md5Hex(source.getBytes(java.nio.charset.StandardCharsets.UTF_8).nn)
    val docs = Vector(s.TextDocument(uri = uri, md5 = md5))
    val tools = Mcp.toolsFor(Analyzer(SemanticIndex(docs)), root)
    val res = tools
      .find(_.name == "annotated_source")
      .getOrElse(fail("no annotated_source"))
      .run(ujson.Obj("uri" -> uri))
    assertEquals(res.obj.contains("pcFallback"), false, res.render())
