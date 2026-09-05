package com.github.mercurievv.scalasemantic.analysis

class SourceSentinelSuite extends munit.FunSuite:

  test("inject appends a /*SEM:...:SEM*/ block to the end of the noted line, line count unchanged"):
    val lines = Vector("object Foo:", "  def bar = 1")
    val out = SourceSentinel.inject(lines, List(SourceSentinel.Note(1, "type=Int")))
    assertEquals(out, Vector("object Foo:", "  def bar = 1 /*SEM:type=Int:SEM*/"))

  test("inject with no notes returns lines unchanged"):
    val lines = Vector("val x = 1", "val y = 2")
    assertEquals(SourceSentinel.inject(lines, Nil), lines)

  test("inject with two notes on the same line joins them in one block, in note order"):
    val lines = Vector("val x = 1")
    val out = SourceSentinel.inject(
      lines,
      List(SourceSentinel.Note(0, "a"), SourceSentinel.Note(0, "b"))
    )
    assertEquals(out, Vector("val x = 1 /*SEM:a; b:SEM*/"))

  test("inject preserves a real trailing comment already on the noted line, block goes after it"):
    val lines = Vector("  def bar = 1 // real trailing comment")
    val out = SourceSentinel.inject(lines, List(SourceSentinel.Note(0, "type=Int")))
    assertEquals(out, Vector("  def bar = 1 // real trailing comment /*SEM:type=Int:SEM*/"))

  test("strip removes only the /*SEM:...:SEM*/ block, leaving everything else untouched"):
    val lines = Vector(
      "  def bar = 1 // a real trailing comment /*SEM:type=Int:SEM*/",
      "  /** a real doc comment */"
    )
    assertEquals(
      SourceSentinel.strip(lines),
      Vector("  def bar = 1 // a real trailing comment", "  /** a real doc comment */")
    )

  test("strip is a no-op when there is no /*SEM:...:SEM*/ block"):
    val lines = Vector("// TODO: real comment", "val x = 1")
    assertEquals(SourceSentinel.strip(lines), lines)

  test("strip leaves real text that follows the end marker on the same line"):
    val lines = Vector("val x = 1 /*SEM:type=Int:SEM*/ // trailing note added after the block")
    assertEquals(
      SourceSentinel.strip(lines),
      Vector("val x = 1 // trailing note added after the block")
    )

  test("strip does not touch bare SEM:...:SEM text that is not wrapped in /* */"):
    // The `/* */` wrapper is required — bare "SEM:...:SEM" (e.g. typed by a human, or leftover
    // from a stale format) is not recognized as a sentinel and is left alone.
    val lines = Vector("// see SEM:this:SEM in the docs")
    assertEquals(SourceSentinel.strip(lines), lines)

  test("inject then strip is the identity on the original lines"):
    val lines = Vector("object Foo:", "  def bar = 1", "  val z = 2")
    val notes = List(SourceSentinel.Note(1, "type=Int"), SourceSentinel.Note(2, "type=Int"))
    assertEquals(SourceSentinel.strip(SourceSentinel.inject(lines, notes)), lines)

  test("strip leaves a marker that is string DATA, not an injected block"):
    // A source may legitimately contain the marker as text. Stripping it wrote the file back as
    // `val marker = ""` — silent loss of the author's own content, the worst failure this path has.
    val lines = Vector("""  val marker = "/*SEM:in-a-string:SEM*/"""")
    assertEquals(SourceSentinel.strip(lines), lines)

  test("strip removes the injected block but keeps an identical-looking one inside a literal"):
    val lines = Vector("""  val marker = "/*SEM:data:SEM*/" /*SEM:type: String:SEM*/""")
    assertEquals(SourceSentinel.strip(lines), Vector("""  val marker = "/*SEM:data:SEM*/""""))

  test("strip removes two injected blocks on one line without eating the code between them"):
    val lines = Vector("val a = 1 /*SEM:x:SEM*/ + f() /*SEM:y:SEM*/")
    assertEquals(SourceSentinel.strip(lines), Vector("val a = 1 + f()"))

  test("strip is not fooled by an escaped quote before the marker"):
    val lines = Vector("""  val s = "a\"b" /*SEM:type: String:SEM*/""")
    assertEquals(SourceSentinel.strip(lines), Vector("""  val s = "a\"b""""))

  test("inject then strip is the identity on a line whose text already contains the marker"):
    val lines = Vector("""val marker = "/*SEM:data:SEM*/"""")
    val notes = List(SourceSentinel.Note(0, "type: String"))
    assertEquals(SourceSentinel.strip(SourceSentinel.inject(lines, notes)), lines)
