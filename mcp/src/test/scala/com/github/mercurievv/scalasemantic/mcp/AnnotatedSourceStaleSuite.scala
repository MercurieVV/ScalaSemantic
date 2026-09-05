package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb as s

/** `annotated_source`'s `staleIndex`: whether the file on disk is still the text the annotations
  * were compiled from. SemanticDB stores that source's MD5 in `TextDocument.md5`, so the check is
  * exact — and testable here by handing the index a document whose digest matches (or does not) a
  * file written to a temp dir, with no compiler in the loop.
  */
class AnnotatedSourceStaleSuite extends munit.FunSuite:

  private val Uri = "Foo.scala"
  private val Source = "object Foo:\n  def bar = 1\n"

  private def md5Of(text: String): String =
    McpToolsSupport.md5Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).nn)

  private def setup(onDisk: String, compiledFrom: Option[String]): (Path, ujson.Value) =
    val root = Files.createTempDirectory("ss-stale").nn
    val _ = Files.writeString(root.resolve(Uri), onDisk)
    val docs = compiledFrom.map(t => s.TextDocument(uri = Uri, md5 = md5Of(t))).toVector
    val tools = Mcp.toolsFor(Analyzer(SemanticIndex(docs)), root)
    val res = tools
      .find(_.name == "annotated_source")
      .getOrElse(fail("no annotated_source"))
      .run(ujson.Obj("uri" -> Uri))
    (root, res)

  test("a file matching the digest the index recorded is not stale"):
    val (_, res) = setup(onDisk = Source, compiledFrom = Some(Source))
    assertEquals(res("staleIndex").bool, false)
    assertEquals(res.obj.contains("staleHint"), false)

  test("a file edited since that compile is stale, and says what to do about it"):
    val (_, res) = setup(onDisk = Source + "  def baz = 2\n", compiledFrom = Some(Source))
    assertEquals(res("staleIndex").bool, true)
    val hint = res("staleHint").str
    assert(hint.contains(Uri), hint)
    assert(hint.contains("refresh_workspace"), hint)
    // The SOURCE is always the current file — only the annotations can be behind.
    assert(res("source").str.contains("def baz = 2"), res("source").str)

  test("no recorded digest means the question is unanswerable, so the field is absent"):
    val (_, res) = setup(onDisk = Source, compiledFrom = None)
    // `md5 = ""` is what a document without a digest carries; absence must not read as "current".
    assertEquals(res.obj.contains("staleIndex"), false)
    assertEquals(res.obj.contains("staleHint"), false)
