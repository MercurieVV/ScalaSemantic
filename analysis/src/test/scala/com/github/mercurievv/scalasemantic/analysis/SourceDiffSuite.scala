package com.github.mercurievv.scalasemantic.analysis

class SourceDiffSuite extends munit.FunSuite:

  private def lines(s: String): IndexedSeq[String] = s.split("\n", -1).toIndexedSeq

  private val base =
    """|object Fixture:
       |  given byLength: Ordering[String] = Ordering.by(s => s.length)
       |
       |  def sizes(xs: List[String]) = xs.map(s => s.length)
       |
       |  def longest(xs: List[String]) = xs.max
       |
       |  val total = sizes(List("a", "bb", "ccc")).sum
       |""".stripMargin

  test("identical versions produce no diff") {
    assertEquals(SourceDiff.unified("A.scala", lines(base), lines(base)), "")
  }

  test("a renamed method shows as one hunk per changed line") {
    val edited = base.replace("sizes", "lengths")
    val d = SourceDiff.unified("A.scala", lines(base), lines(edited))
    assert(d.startsWith("--- A.scala (on disk)\n+++ A.scala (written)\n@@ "), d)
    assert(d.contains("-  def sizes(xs: List[String]) = xs.map(s => s.length)"), d)
    assert(d.contains("+  def lengths(xs: List[String]) = xs.map(s => s.length)"), d)
    assert(d.contains("""-  val total = sizes(List("a", "bb", "ccc")).sum"""), d)
    assert(d.contains("""+  val total = lengths(List("a", "bb", "ccc")).sum"""), d)
    // Unchanged lines between the two edits stay as context, not as churn.
    assert(d.contains("   def longest(xs: List[String]) = xs.max"), d)
  }

  test("an inserted line is an addition, not a rewrite of everything below it") {
    val edited = base.replace(
      "  def longest",
      "  def shortest(xs: List[String]) = xs.min\n\n  def longest"
    )
    val d = SourceDiff.unified("A.scala", lines(base), lines(edited))
    assertEquals(d.linesIterator.count(_.startsWith("+")), 3) // +++ header, the def, its blank line
    assertEquals(d.linesIterator.count(_.startsWith("-")), 1) // --- header only
    assert(d.contains("+  def shortest(xs: List[String]) = xs.min"), d)
  }

  test("a deleted line is a deletion") {
    val edited = base.replace("  def longest(xs: List[String]) = xs.max\n\n", "")
    val d = SourceDiff.unified("A.scala", lines(base), lines(edited))
    assert(d.contains("-  def longest(xs: List[String]) = xs.max"), d)
    assertEquals(d.linesIterator.count(_.startsWith("+")), 1) // the +++ header alone
  }

  test("hunk headers carry 1-based counts matching the lines they cover") {
    val edited = base.replace("xs.max", "xs.maxOption.getOrElse(\"\")")
    val d = SourceDiff.unified("A.scala", lines(base), lines(edited))
    val header = d.linesIterator.find(_.startsWith("@@")).getOrElse("")
    val re = """@@ -(\d+),(\d+) \+(\d+),(\d+) @@""".r
    header match
      case re(os, oc, ns, nc) =>
        val hunk = d.linesIterator.dropWhile(!_.startsWith("@@")).drop(1).toList
        assertEquals(hunk.count(l => l.startsWith(" ") || l.startsWith("-")), oc.toInt)
        assertEquals(hunk.count(l => l.startsWith(" ") || l.startsWith("+")), nc.toInt)
        assertEquals(os.toInt, 3) // the change is on line 6, minus 3 lines of context
        assertEquals(ns.toInt, 3)
      case _ => fail(s"no hunk header in:\n$d")
  }

  test("far-apart edits are separate hunks, adjacent ones are merged") {
    val long = (1 to 40).map(i => s"line $i").mkString("\n")
    val twoEdits = long.replace("line 2\n", "CHANGED 2\n").replace("line 39", "CHANGED 39")
    val far = SourceDiff.unified("A.scala", lines(long), lines(twoEdits))
    assertEquals(far.linesIterator.count(_.startsWith("@@")), 2)

    val nearEdits = long.replace("line 20\n", "CHANGED 20\n").replace("line 22", "CHANGED 22")
    val near = SourceDiff.unified("A.scala", lines(long), lines(nearEdits))
    assertEquals(near.linesIterator.count(_.startsWith("@@")), 1)
  }

  test("empty on both sides, and file emptied entirely") {
    assertEquals(SourceDiff.unified("A.scala", lines(""), lines("")), "")
    val d = SourceDiff.unified("A.scala", lines("a\nb\n"), lines(""))
    assert(d.contains("-a"), d)
    assert(d.contains("-b"), d)
  }
