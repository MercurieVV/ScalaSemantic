package com.github.mercurievv.scalasemantic.semanticdb

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb as s

/** Coverage compares the `*.scala` on disk against the documents actually loaded.
  *
  * The motivating report: 31 of 82 sources (every `*.test.scala`) had no SemanticDB entry because
  * the build compiled the main scope only, and the server had no way to notice — `find_symbol` for
  * a class defined in an unindexed file answered `count: 0`, identical to "does not exist".
  */
class SemanticIndexCoverageSuite extends munit.FunSuite:

  private def tempRoot(name: String): Path = Files.createTempDirectory(name).nn

  private def writeSource(root: Path, rel: String): Unit =
    val p = root.resolve(rel).nn
    Option(p.getParent).foreach(Files.createDirectories(_))
    val _ = Files.write(p, "class X\n".getBytes("UTF-8"))

  private def indexOf(uris: String*): SemanticIndex =
    new SemanticIndex(uris.toVector.map(u => s.TextDocument(uri = u)))

  test("reports the sources that carry no document") {
    val root = tempRoot("coverage-gap")
    writeSource(root, "src/Main.scala")
    writeSource(root, "src/Main.test.scala")
    writeSource(root, "src/Other.test.scala")

    val cov = SemanticIndex.coverage(Seq(root), indexOf("src/Main.scala"))

    assertEquals(cov.sources, 3)
    assertEquals(cov.indexed, 1)
    assert(cov.partial, "1 of 3 indexed is a partial index")
    assertEquals(cov.unindexed.toSet, Set("src/Main.test.scala", "src/Other.test.scala"))
  }

  test("full coverage is not partial and lists nothing") {
    val root = tempRoot("coverage-full")
    writeSource(root, "a/A.scala")
    writeSource(root, "b/B.scala")

    val cov = SemanticIndex.coverage(Seq(root), indexOf("a/A.scala", "b/B.scala"))

    assertEquals(cov.sources, 2)
    assertEquals(cov.indexed, 2)
    assert(!cov.partial, "every source has a document")
    assertEquals(cov.unindexed, Nil)
  }

  // A document's `uri` is relative to the compiler's `-sourceroot`, which is not always the
  // project root. Matching on equality alone would report every file of such a module as a gap.
  test("matches when the document uri is relative to a module rather than the root") {
    val root = tempRoot("coverage-sourceroot")
    writeSource(root, "core/src/main/scala/Foo.scala")

    val cov = SemanticIndex.coverage(Seq(root), indexOf("src/main/scala/Foo.scala"))

    assertEquals(cov.indexed, 1)
    assert(!cov.partial, s"suffix-relative uri should match: ${cov.unindexed}")
  }

  // Same-named files in different directories must not cross-match.
  test("same file name in another directory does not count as covered") {
    val root = tempRoot("coverage-samename")
    writeSource(root, "a/Foo.scala")
    writeSource(root, "b/Foo.scala")

    val cov = SemanticIndex.coverage(Seq(root), indexOf("a/Foo.scala"))

    assertEquals(cov.indexed, 1)
    assertEquals(cov.unindexed, Seq("b/Foo.scala"))
  }

  // Mill and sbt copy or generate sources under out/ and target/; counting those as project
  // sources would report a permanent, meaningless gap.
  test("build output directories are not scanned for sources") {
    val root = tempRoot("coverage-out")
    writeSource(root, "src/Main.scala")
    writeSource(root, "out/core/compile.dest/Generated.scala")
    writeSource(root, "target/scala-3.8.4/src_managed/Managed.scala")

    val cov = SemanticIndex.coverage(Seq(root), indexOf("src/Main.scala"))

    assertEquals(cov.sources, 1)
    assert(!cov.partial, s"build output should be skipped, got ${cov.unindexed}")
  }

  test("unindexed list is capped but the counts stay exact") {
    val root = tempRoot("coverage-cap")
    (1 to SemanticIndex.Coverage.MaxListed + 5).foreach(i => writeSource(root, s"src/F$i.scala"))

    val cov = SemanticIndex.coverage(Seq(root), indexOf())

    assertEquals(cov.sources, SemanticIndex.Coverage.MaxListed + 5)
    assertEquals(cov.indexed, 0)
    assertEquals(cov.unindexed.size, SemanticIndex.Coverage.MaxListed)
  }

  test("dogfood: this repo's own sources are fully covered by its own index") {
    val root = java.nio.file.Paths.get(".").nn.toAbsolutePath.nn.normalize().nn
    val cov = SemanticIndex.coverage(Seq(root), SemanticIndex.fromProject("."))
    assert(cov.sources > 0, "the repo has Scala sources")
    assert(cov.indexed > 0, "and documents for them")
  }
