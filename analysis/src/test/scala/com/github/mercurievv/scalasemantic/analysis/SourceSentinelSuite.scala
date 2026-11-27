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

  // #351: a multi-line literal's continuation lines are string content. A block appended there
  // can't be stripped without guessing, so inject never puts one there and strip never takes one.
  private val tripleQuoted = Vector(
    "val content: String =",
    "  \"\"\"|// Generated header",
    "     |import sbt._",
    "     |\"\"\".stripMargin"
  )

  test("a note for a line inside a multi-line string moves to the line the string closes on"):
    val out =
      SourceSentinel.inject(tripleQuoted, List(SourceSentinel.Note(1, "elaborated: augmentString")))
    assertEquals(out.take(3), tripleQuoted.take(3))
    assertEquals(out(3), "     |\"\"\".stripMargin /*SEM:elaborated: augmentString:SEM*/")
    assertEquals(SourceSentinel.strip(out), tripleQuoted)

  test("strip keeps a marker that is data on a continuation line of a multi-line string"):
    val lines = Vector("val s = \"\"\"first", "  /*SEM:data:SEM*/ second", "  \"\"\"")
    assertEquals(SourceSentinel.strip(lines), lines)

  test("strip removes a block that a stray quote in a line comment would have hidden"):
    val lines = Vector("val x = 1 // it's \"quoted /*SEM:type: Int:SEM*/")
    assertEquals(SourceSentinel.strip(lines), Vector("val x = 1 // it's \"quoted"))

  test("strip removes a block after a quote character literal"):
    val lines = Vector("val q = '\"' /*SEM:type: Char:SEM*/")
    assertEquals(SourceSentinel.strip(lines), Vector("val q = '\"'"))

  test("a payload holding comment delimiters stays one block and still round-trips"):
    val lines = Vector("val r = raw\"a*/b\".r")
    val out = SourceSentinel.inject(lines, List(SourceSentinel.Note(0, "augmentString(a*/b /* c)")))
    assertEquals(out, Vector("val r = raw\"a*/b\".r /*SEM:augmentString(a* /b / * c):SEM*/"))
    assertEquals(SourceSentinel.strip(out), lines)

  test("anchorOf follows a multi-line interpolation to its closing line"):
    val lines = Vector("val s = s\"\"\"a ${x}", "b", "\"\"\"", "val y = 1")
    val anchor = SourceSentinel.anchorOf(lines)
    assertEquals((0 until 4).map(anchor).toList, List(2, 2, 2, 3))

  test("strip keeps a marker quoted inside a doc comment"):
    val lines = Vector("  /** only `/*SEM:...:SEM*/` blocks are strippable */", "  def f = 1")
    assertEquals(SourceSentinel.strip(lines), lines)

  test("a note for a line inside a multi-line doc comment moves past the comment's end"):
    val lines = Vector("/** doc", "  * more", "  */", "def f = 1")
    val out = SourceSentinel.inject(lines, List(SourceSentinel.Note(0, "x")))
    assertEquals(out(2), "  */ /*SEM:x:SEM*/")
    assertEquals(SourceSentinel.strip(out), lines)
