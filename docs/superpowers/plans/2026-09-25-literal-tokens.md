# `literal_tokens` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A syntax-only MCP tool, `literal_tokens`, that returns the literals in given line ranges of given Scala files — each with a normalised value and its nearest enclosing definition — so a harness can compare production literals with test literals.

**Architecture:** `LiteralTokens` (in `analysis`, beside `NameTokens`) parses text with scalameta's Scala 3 dialect and collects `Lit` nodes, returning the `model` types directly. The `mcp` layer adds one tool entry beside `resolve_references` that reads each file (or its `source`) and serialises the result. No presentation compiler and no classpath: the tool answers wherever the file parses.

**Tech Stack:** Scala 3, scalameta 4.17.0, upickle, munit, Mill 1.1.7.

**Spec:** `/Users/viktorskalinins/IdeaProjects/my/scala-coding-guidelines/docs/superpowers/specs/2026-09-25-purrism-loop-and-memorisation-design.md` — §1 is this plan. §2–§4 are the skreeep2 plan.

## Global Constraints

- Input: `files`, a list of `{uri, ranges?, source?}`. `uri` is the SemanticDB-form path relative to the project root; `ranges` is a list of `[startLine, endLine]`, **0-based, end exclusive**; `source` is the buffer's current text (absent → the on-disk file). **`ranges` absent means the whole file**, and so does an explicit empty `ranges: []` (the `NameTokens` convention).
- `kind` is exactly one of `int` | `long` | `double` | `float` | `string` | `char`. Booleans, `null` and `()` are **excluded**.
- `value` is normalised so equal literals compare equal across files: `0x10` and `16` are both `16`, `1_000` is `1000`, string escapes are resolved.
- String interpolations contribute their literal parts: `s"note $n"` yields `"note "`.
- `enclosing` is the nearest enclosing definition with its kind, name and line.
- Bound: **2,000** literals per file; `omitted` counts what the bound cut.
- A buffer that does not parse answers that file with an `error`, never a partial list.
- **No policy in the tool**: it does not know which files are tests, sets no threshold, and drops no "trivial" literal (`0` and `1` are returned).
- Every commit message ends with exactly this line, and never carries a `Co-Authored-By` trailer:
  ```
  Claude-Session: https://claude.ai/code/session_01TEcqcxKmBFZQupRykQoDMX
  ```
- `./mill __.test` and `./mill __.checkFormat` must be green at every commit. `regressions/e2e-tests.json` must stay byte-identical.

## Ruling carried into this plan (not in the spec)

The spec lists `enclosing` kinds as `def | val | var | object`. A literal can also sit directly in a `class`, `trait`, `enum` or `given` body, or outside every definition. **Ruling:** the kinds are widened to `def | val | var | object | class | trait | enum | given`, nearest first, and `enclosing` is **absent** (JSON `null`) when no such definition encloses the literal. The harness groups such literals by file. Cost if wrong: a caller expecting only four kinds sees four more — additive, and the grouping still works.

## Review Focus

The inputs the spec implies that its test list does not exercise, most likely to bite first:

1. **A negative literal** — `-5` in a production table and `-5` in a test must compare equal. scalameta may parse it as `Lit.Int(-5)` or as a unary minus applied to `Lit.Int(5)`; either way the reported `value` must be `-5` and exactly one row must be produced. (Task 1, test "a negative literal is one row with a negative value")
2. **CRLF line endings** — a file saved with `\r\n` must report the same 0-based `line` as git's ranges, or a harness filtering by range silently drops rows. (Task 1, test "CRLF line endings keep line numbers")
3. **An explicit empty `ranges: []`** — must mean the whole file, not "no lines". (Task 2, test "an explicit empty ranges list means the whole file")
4. **A file that exists neither on disk nor as `source`** — must be one per-file `error`, not a failed call that loses the other files' answers. (Task 2, test "a missing file is a per-file error, the others still answer")
5. **A literal outside every definition, and one in a `class` body** — must not crash and must follow the ruling above. (Task 1, test "enclosing covers class bodies and is absent outside every definition")

---

### Task 1: `LiteralTokens` and its model

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/LiteralResult.scala`
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/LiteralTokens.scala`
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/LiteralTokensSuite.scala`
- Modify: `regressions/unit-tests.json`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `model.Literal(line: Int, character: Int, kind: String, value: String, enclosing: Option[model.Enclosing])`
  - `model.Enclosing(kind: String, name: String, line: Int)`
  - `model.FileLiterals(uri: String, literals: List[Literal], omitted: Int, error: Option[String])`
  - `model.LiteralResult(files: List[FileLiterals])` — all `derives ReadWriter`.
  - `LiteralTokens.in(text: String, ranges: Seq[(Int, Int)]): Option[List[Literal]]` — `None` when the text does not parse; source order.
  - `LiteralTokens.forFile(uri: String, text: String, ranges: Seq[(Int, Int)]): FileLiterals` — applies the 2,000 bound and turns a parse failure into `error`.
  - `LiteralTokens.MaxPerFile: Int = 2000`.

- [ ] **Step 1: Write the model**

`analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/LiteralResult.scala`:

```scala
package com.github.mercurievv.scalasemantic.model

import upickle.default.*

/** The nearest definition enclosing a literal (spec 2026-09-25-purrism-loop-and-memorisation-design
  * §1). It is what lets a caller judge a CONSTRUCT rather than a line: a table that memorises test
  * inputs is one `val` or one `def`, whatever its length. `kind` is one of `def`, `val`, `var`,
  * `object`, `class`, `trait`, `enum`, `given`; `line` is the definition's own 0-based start line.
  */
final case class Enclosing(kind: String, name: String, line: Int) derives ReadWriter

/** One literal the source text contains. Syntax only.
  *
  *   - `kind` is `int`, `long`, `double`, `float`, `string` or `char`. Booleans, `null` and `()`
  *     are never reported: they carry no signal about what a program was written to recognise.
  *   - `value` is normalised so the same literal written two ways compares equal: `0x10` and `16`
  *     are both `16`, `1_000` is `1000`, and a string's escapes are resolved.
  *   - `enclosing` is absent when no definition encloses the literal.
  */
final case class Literal(
    line: Int,
    character: Int,
    kind: String,
    value: String,
    enclosing: Option[Enclosing]
) derives ReadWriter

/** Every literal of one file's requested ranges, in source order. `omitted` counts the literals the
  * 2,000-per-file bound dropped. `error` is set — with `literals` empty — when the file could not be
  * read or parsed: zero literals and a syntax error are different answers and must not be confused.
  */
final case class FileLiterals(
    uri: String,
    literals: List[Literal],
    omitted: Int,
    error: Option[String]
) derives ReadWriter

/** The wire result: one entry per file asked about. */
final case class LiteralResult(files: List[FileLiterals]) derives ReadWriter
```

- [ ] **Step 2: Write the failing tests**

`analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/LiteralTokensSuite.scala`:

```scala
package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.{Enclosing, Literal}

class LiteralTokensSuite extends munit.FunSuite:

  private def lits(src: String, ranges: Seq[(Int, Int)] = Nil): List[Literal] =
    LiteralTokens.in(src, ranges).getOrElse(fail("did not parse"))

  private def kv(src: String): List[(String, String)] = lits(src).map(l => l.kind -> l.value)

  test("each kind is reported with a normalised value") {
    val src =
      """package p
        |object O:
        |  val a = 0x10
        |  val b = 1_000
        |  val c = 7L
        |  val d = 2.5
        |  val e = 1.5f
        |  val f = 'x'
        |  val g = "tab\there"
        |""".stripMargin
    assertEquals(
      kv(src),
      List(
        "int" -> "16",
        "int" -> "1000",
        "long" -> "7",
        "double" -> "2.5",
        "float" -> "1.5",
        "char" -> "x",
        "string" -> "tab\there"
      )
    )
  }

  test("booleans, null and unit are not reported") {
    val src =
      """package p
        |object O:
        |  val a = true
        |  val b = null
        |  val c = ()
        |  val d = 3
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "3"))
  }

  test("an interpolation contributes its literal parts") {
    val src =
      """package p
        |object O:
        |  def f(n: Int) = s"note $n end"
        |""".stripMargin
    assertEquals(kv(src), List("string" -> "note ", "string" -> " end"))
  }

  test("a negative literal is one row with a negative value") {
    val src =
      """package p
        |object O:
        |  val a = -5
        |  val b = -2.5
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "-5", "double" -> "-2.5"))
  }

  test("a literal pattern in a match case is reported") {
    val src =
      """package p
        |object O:
        |  def f(x: Int): Int = x match
        |    case 3 => 42
        |    case _ => 0
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "3", "int" -> "42", "int" -> "0"))
  }

  test("enclosing is the nearest definition: a val table, a match in a def, a nested object") {
    val src =
      """package p
        |object Outer:
        |  val table = Map("C4" -> 60)
        |  def f(x: Int): Int = x match
        |    case 7 => 1
        |  object Inner:
        |    val z = 99
        |""".stripMargin
    val byValue = lits(src).map(l => l.value -> l.enclosing).toMap
    assertEquals(byValue("C4"), Some(Enclosing("val", "table", 2)))
    assertEquals(byValue("60"), Some(Enclosing("val", "table", 2)))
    assertEquals(byValue("7"), Some(Enclosing("def", "f", 3)))
    assertEquals(byValue("99"), Some(Enclosing("val", "z", 6)))
  }

  test("enclosing covers class bodies and is absent outside every definition") {
    // A top-level type alias is not one of the enclosing kinds, so its literal type has no
    // enclosing definition; a statement in a class body is enclosed by the class.
    val src =
      """package p
        |type Three = 3
        |class C:
        |  println(5)
        |""".stripMargin
    val all = lits(src)
    val five = all.find(_.value == "5").getOrElse(fail(all.toString))
    assertEquals(five.enclosing, Some(Enclosing("class", "C", 2)))
    val three = all.find(_.value == "3").getOrElse(fail(all.toString))
    assertEquals(three.enclosing, None)
  }

  test("ranges select by start line; absent ranges mean the whole file") {
    val src =
      """package p
        |object O:
        |  val a = 11
        |  val b = 22
        |  val c = 33
        |""".stripMargin
    assertEquals(lits(src).map(_.value), List("11", "22", "33"))
    assertEquals(lits(src, Seq((3, 4))).map(_.value), List("22"))
  }

  test("CRLF line endings keep line numbers") {
    val src = "package p\r\nobject O:\r\n  val a = 11\r\n  val b = 22\r\n"
    assertEquals(lits(src).map(l => l.line -> l.value), List(2 -> "11", 3 -> "22"))
  }

  test("a file that does not parse yields None, not an empty list") {
    assertEquals(LiteralTokens.in("object O { val = }", Nil), None)
  }

  test("forFile turns a parse failure into an error") {
    val r = LiteralTokens.forFile("A.scala", "object O { val = }", Nil)
    assertEquals(r.literals, Nil)
    assert(r.error.exists(_.nonEmpty), r.toString)
  }

  test("forFile bounds the answer at 2,000 literals and counts the rest") {
    val body = (1 to 2500).map(i => s"  val v$i = ${i + 100}").mkString("\n")
    val src = s"package p\nobject O:\n$body\n"
    val r = LiteralTokens.forFile("A.scala", src, Nil)
    assertEquals(r.literals.size, LiteralTokens.MaxPerFile)
    assertEquals(r.omitted, 500)
    assertEquals(r.literals.head.value, "101")
    assertEquals(r.error, None)
  }
```

- [ ] **Step 3: Run the tests and watch them fail**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.LiteralTokensSuite`
Expected: compilation failure — `LiteralTokens` is not defined.

- [ ] **Step 4: Implement `LiteralTokens`**

`analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/LiteralTokens.scala`:

```scala
package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.{Enclosing, FileLiterals, Literal}
import scala.meta.*
import scala.util.Try

/** Every literal the source text contains, with a normalised value and its nearest enclosing
  * definition (spec 2026-09-25-purrism-loop-and-memorisation-design §1). Syntax only — the
  * sibling of [[NameTokens]], and like it needs no presentation compiler and no classpath.
  *
  * No policy lives here: which files are tests, what counts as trivial and what overlap is
  * suspicious are the caller's decisions, so `0` and `1` are returned like any other literal.
  */
object LiteralTokens:

  val MaxPerFile: Int = 2000

  /** Every literal of `text` whose start line falls inside one of `ranges` (`[startLine,
    * endLineExclusive)`, 0-based); an empty `ranges` means the whole file. `None` when the text does
    * not parse.
    */
  def in(text: String, ranges: Seq[(Int, Int)]): Option[List[Literal]] =
    Try(dialects.Scala3(text).parse[Source].get).toOption.map { tree =>
      val inRange: Position => Boolean =
        if ranges.isEmpty then _ => true
        else pos => ranges.exists((from, until) => pos.startLine >= from && pos.startLine < until)
      tree
        .collect {
          // A negative literal: report it once, with its sign, whichever shape the parser chose.
          case u @ Term.ApplyUnary(Term.Name("-"), l: Lit) if inRange(u.pos) =>
            literal(u, l, negative = true)
          case l: Lit if inRange(l.pos) && !isNegatedOperand(l) =>
            literal(l, l, negative = false)
        }
        .flatten
        .sortBy(l => (l.line, l.character))
    }

  /** [[in]] for one file, with the output bound applied and a parse failure turned into `error`. */
  def forFile(uri: String, text: String, ranges: Seq[(Int, Int)]): FileLiterals =
    in(text, ranges) match
      case None => FileLiterals(uri, Nil, 0, Some(s"$uri does not parse as Scala 3"))
      case Some(all) =>
        FileLiterals(uri, all.take(MaxPerFile), (all.size - MaxPerFile).max(0), None)

  private def isNegatedOperand(l: Lit): Boolean =
    l.parent.exists {
      case Term.ApplyUnary(Term.Name("-"), arg) => arg eq l
      case _                                     => false
    }

  /** `at` is the tree whose position is reported (the unary minus, for a negative literal); `lit`
    * is the literal itself.
    */
  private def literal(at: Tree, lit: Lit, negative: Boolean): Option[Literal] =
    kindAndValue(lit).map { (kind, value) =>
      val signed = if negative then s"-$value" else value
      Literal(at.pos.startLine, at.pos.startColumn, kind, signed, enclosingOf(at))
    }

  private def kindAndValue(lit: Lit): Option[(String, String)] =
    lit match
      case Lit.Int(v)    => Some("int" -> v.toString)
      case Lit.Long(v)   => Some("long" -> v.toString)
      case d: Lit.Double => Some("double" -> d.value.toString)
      case f: Lit.Float  => Some("float" -> f.value.toString)
      case Lit.Char(v)   => Some("char" -> v.toString)
      case Lit.String(v) => Some("string" -> v)
      case _             => None // Boolean, Null, Unit, Symbol: no signal

  private def enclosingOf(t: Tree): Option[Enclosing] =
    Iterator
      .iterate(t.parent)(_.flatMap(_.parent))
      .takeWhile(_.isDefined)
      .flatten
      .collectFirst {
        case d: Defn.Def    => Enclosing("def", d.name.value, d.pos.startLine)
        case d: Defn.Val    => Enclosing("val", patName(d.pats), d.pos.startLine)
        case d: Defn.Var    => Enclosing("var", patName(d.pats), d.pos.startLine)
        case d: Defn.Object => Enclosing("object", d.name.value, d.pos.startLine)
        case d: Defn.Class  => Enclosing("class", d.name.value, d.pos.startLine)
        case d: Defn.Trait  => Enclosing("trait", d.name.value, d.pos.startLine)
        case d: Defn.Enum   => Enclosing("enum", d.name.value, d.pos.startLine)
        case d: Defn.Given  => Enclosing("given", givenName(d.name), d.pos.startLine)
      }

  private def patName(pats: List[Pat]): String =
    pats.collectFirst { case Pat.Var(n) => n.value }.getOrElse("<pattern>")

  private def givenName(n: Name): String =
    if n.value.isEmpty then "<anonymous>" else n.value
```

**Notes for the implementer — verify, do not assume:**
- scalameta may already fold `-5` into `Lit.Int(-5)`. If it does, the `Term.ApplyUnary` case never fires and `Lit.Int(v)` already carries the sign — the test "a negative literal is one row with a negative value" passes either way. If it does NOT fold and you see two rows or a missing sign, the two cases above are what fixes it. Report which shape you measured.
- The CRLF test pins `pos.startLine`. If scalameta counts `\r\n` differently from git, normalise the text before parsing (`text.replace("\r\n", "\n")`) **and** keep columns consistent. Report what you measured.
- `Lit.String`'s `value` for a plain string is already unescaped by the parser; the test "each kind is reported with a normalised value" pins `"tab\there"` as containing a real tab. An interpolation part's `value` may be the raw text — the interpolation test uses no escapes, so it does not depend on that.
- If `Defn.Var`'s `pats` field has a different name in scalameta 4.17.0, use whatever it is called there; the test pins the behaviour, not the field name.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.LiteralTokensSuite`
Expected: 12 tests, all pass.

- [ ] **Step 6: Register the tests and run the gates**

Add the twelve new test names to `regressions/unit-tests.json`, keeping the file's sorted order. Each entry is `"com.github.mercurievv.scalasemantic.analysis.LiteralTokensSuite.<test name>"`, exactly as the `NameTokensSuite` entries are written.

Run: `./mill __.test` → SUCCESS. `./mill __.checkFormat` → SUCCESS (run `./mill __.reformat` first if it reports a misformatted file).

- [ ] **Step 7: Commit**

```bash
git add analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/LiteralResult.scala \
        analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/LiteralTokens.scala \
        analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/LiteralTokensSuite.scala \
        regressions/unit-tests.json
git commit -F - <<'MSG'
analysis: LiteralTokens — every literal in a range, normalised, with its enclosing definition

The syntax fact behind the harness's memorisation check: which literals a construct holds, so a
caller can compare production literals with the literals its tests use. Values are normalised so a
literal written two ways compares equal, and each carries its nearest enclosing definition because a
memorising table is one construct whatever its length. No policy here — the tool drops no "trivial"
literal and does not know which files are tests.

Claude-Session: https://claude.ai/code/session_01TEcqcxKmBFZQupRykQoDMX
MSG
```

---

### Task 2: The `literal_tokens` MCP tool

**Files:**
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala`
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpSuite.scala`
- Modify: `regressions/unit-tests.json`

**Interfaces:**
- Consumes: `LiteralTokens.forFile(uri, text, ranges): FileLiterals` and `model.FileLiterals` (Task 1).
- Produces: the tool `literal_tokens`, argument `files` (required). The server's tool count becomes **30**.

**Background.** `resolve_references` (registered in `McpTools.scala`, search for `"resolve_references"`) is the model: same argument shape, same `argUri`, `readFileText`, `error` helpers in scope. It parses `ranges` inline; this task moves that parsing into one helper both tools use, so the two cannot drift. `literal_tokens` is simpler than its model — it never needs the presentation compiler, so it has no "no presentation compiler" branch and never fails the whole call for a missing backend.

- [ ] **Step 1: Write the failing tests**

Add to `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpSuite.scala`, beside the other tool tests (the suite's `call(name, args)` helper invokes a tool and parses the JSON in its text content block):

```scala
  test("literal_tokens answers from `source` without a presentation compiler") {
    val src = "package p\nobject O:\n  val table = Map(\"C4\" -> 60)\n"
    val r = call(
      "literal_tokens",
      ujson.Obj("files" -> ujson.Arr(ujson.Obj("uri" -> "A.scala", "source" -> src)))
    )
    val file = r("files")(0)
    assertEquals(file("uri").str, "A.scala")
    assertEquals(file("omitted").num.toInt, 0)
    val rows = file("literals").arr.map(l => l("kind").str -> l("value").str).toList
    assertEquals(rows, List("string" -> "C4", "int" -> "60"))
    assertEquals(file("literals")(0)("enclosing")("name").str, "table")
  }

  test("literal_tokens honours ranges") {
    val src = "package p\nobject O:\n  val a = 11\n  val b = 22\n"
    val r = call(
      "literal_tokens",
      ujson.Obj(
        "files" -> ujson.Arr(
          ujson.Obj("uri" -> "A.scala", "source" -> src, "ranges" -> ujson.Arr(ujson.Arr(3, 4)))
        )
      )
    )
    assertEquals(r("files")(0)("literals").arr.map(_("value").str).toList, List("22"))
  }

  test("an explicit empty ranges list means the whole file") {
    val src = "package p\nobject O:\n  val a = 11\n  val b = 22\n"
    val r = call(
      "literal_tokens",
      ujson.Obj(
        "files" -> ujson.Arr(ujson.Obj("uri" -> "A.scala", "source" -> src, "ranges" -> ujson.Arr()))
      )
    )
    assertEquals(r("files")(0)("literals").arr.map(_("value").str).toList, List("11", "22"))
  }

  test("a missing file is a per-file error, the others still answer") {
    val r = call(
      "literal_tokens",
      ujson.Obj(
        "files" -> ujson.Arr(
          ujson.Obj("uri" -> "does/not/Exist.scala"),
          ujson.Obj("uri" -> "B.scala", "source" -> "package p\nobject B:\n  val x = 7\n")
        )
      )
    )
    val missing = r("files")(0)
    assertEquals(missing("literals").arr.size, 0)
    assert(missing("error").str.contains("no source"), missing.toString)
    assertEquals(r("files")(1)("literals")(0)("value").str, "7")
  }

  test("a file that does not parse is a per-file error") {
    val r = call(
      "literal_tokens",
      ujson.Obj("files" -> ujson.Arr(ujson.Obj("uri" -> "A.scala", "source" -> "object O { val = }")))
    )
    assert(r("files")(0)("error").str.contains("does not parse"), r.toString)
  }

  test("literal_tokens with an empty files list is an error") {
    val text = callResponse("literal_tokens", ujson.Obj("files" -> ujson.Arr()))
      .getOrElse(fail("no response"))("result")("content")(0)("text").str
    assert(text.contains("`files` must hold at least one"), text)
  }
```

Also bump **both** tool-count assertions in `McpSuite` from `29` to `30` — one in the test `tools/list exposes all tools with schemas`, one near line 1195 (`...("tools").arr.size, 29)`) — and add `assert(names.contains("literal_tokens"), names.toString)` beside the other `names.contains` lines.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpSuite`
Expected: the six new tests fail (unknown tool `literal_tokens`), and both tool-count assertions fail with `29 != 30`.

- [ ] **Step 3: Extract the shared ranges parser**

In `McpTools.scala`, inside the `resolve_references` handler, the `ranges` value is computed inline as:

```scala
              val ranges = entry.obj.get("ranges").toSeq.flatMap(_.arr).flatMap { r =>
                val pair = r.arr.toVector.map(_.num.toInt)
                (pair.lift(0), pair.lift(1)) match
                  case (Some(from), Some(until)) => Some((from, until))
                  case _                         => None
              }
```

Replace that expression with `val ranges = argRanges(entry)` and add, next to `argUri` (find its definition in the same file and put this beside it, matching its visibility):

```scala
  /** A `files` entry's `ranges`: `[[startLine, endLineExclusive], …]`, 0-based. Absent, empty, or
    * made only of malformed pairs, it is empty — which every caller reads as "the whole file".
    */
  private def argRanges(entry: ujson.Value): Seq[(Int, Int)] =
    entry.obj.get("ranges").toSeq.flatMap(_.arr).flatMap { r =>
      val pair = r.arr.toVector.map(_.num.toInt)
      (pair.lift(0), pair.lift(1)) match
        case (Some(from), Some(until)) => Some((from, until))
        case _                         => None
    }
```

If `argUri` lives in a different object or companion than the tool list, put `argRanges` in the same place and reference it the way `argUri` is referenced.

- [ ] **Step 4: Add the tool**

Immediately after the `resolve_references` `toolDef(...)` entry, in the same list, add:

```scala
      toolDef(
        tool(
          "literal_tokens",
          "Every LITERAL in the given line ranges of the given files — the syntax fact behind a " +
            "memorisation check that compares production literals with test literals. Syntax only: " +
            "no presentation compiler and no classpath, so it answers wherever the file parses. " +
            "Each row carries `line`/`character` (0-based), `kind` (int/long/double/float/string/" +
            "char — booleans, null and () are never reported), `value` normalised so the same " +
            "literal written two ways compares equal (0x10 and 16 are both \"16\", 1_000 is " +
            "\"1000\", string escapes resolved; an interpolation contributes its literal parts), " +
            "and `enclosing` {kind, name, line}: the nearest enclosing def/val/var/object/class/" +
            "trait/enum/given, or null when none encloses it. `ranges` are [startLine, " +
            "endLineExclusive], 0-based; absent or empty means the whole file. Pass `source` for " +
            "the buffer's text, else the on-disk file is read. No policy: every literal is " +
            "returned, including 0 and 1. OUTPUT: `files` holds {uri, literals, omitted, error} " +
            "per requested file; `error` is null on success, and names the problem (no source, " +
            "does not parse) with `literals` empty otherwise. At most 2000 literals per file, " +
            "`omitted` counting the rest.",
          List(
            (
              "files",
              "array",
              "objects: {uri: string, ranges?: [[startLine, endLineExclusive], …], source?: string}"
            )
          ),
          List("files")
        ) { a =>
          val entries = a.obj.get("files").toList.flatMap(_.arr)
          if entries.isEmpty then
            error("literal_tokens: `files` must hold at least one {uri} object")
          else
            val answers = entries.map { entry =>
              val uri = argUri(entry, "uri")
              val ranges = argRanges(entry)
              val text = entry.obj.get("source").map(_.str).orElse(readFileText(root.resolve(uri.value)))
              text match
                case None =>
                  FileLiterals(
                    uri.value,
                    Nil,
                    0,
                    Some(s"no source: pass `source` or make ${uri.value} exist on disk")
                  )
                case Some(t) => LiteralTokens.forFile(uri.value, t, ranges)
            }
            upickle.default.writeJs(LiteralResult(answers))
        },
```

Add the imports this needs at the top of `McpTools.scala`, beside the existing `analysis`/`model` imports: `com.github.mercurievv.scalasemantic.analysis.LiteralTokens` and `com.github.mercurievv.scalasemantic.model.{FileLiterals, LiteralResult}`.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpSuite`
Expected: all pass, including the six new tests and both tool-count assertions at 30.

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpPcSuite`
Expected: all pass — `resolve_references` still behaves identically after the `argRanges` extraction.

- [ ] **Step 6: Register the tests and run the gates**

Add the six new `McpSuite` test names to `regressions/unit-tests.json` in sorted order, as `"com.github.mercurievv.scalasemantic.mcp.McpSuite.<test name>"`.

Run: `./mill __.test` → SUCCESS. `./mill __.checkFormat` → SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpSuite.scala \
        regressions/unit-tests.json
git commit -F - <<'MSG'
mcp: literal_tokens tool — literals in given ranges, no presentation compiler needed

The same argument shape as resolve_references, so a harness sends both the same `files` list; the
ranges parser is now one helper both tools use, so the two cannot drift. A missing or unparseable
file is a per-file error rather than a failed call, so one bad path never loses the other files'
answers.

Claude-Session: https://claude.ai/code/session_01TEcqcxKmBFZQupRykQoDMX
MSG
```

---

### Task 3: Docs, the rendered example, and the one-shot call

**Files:**
- Modify: `docs/reference/tools.md`
- Modify: `docs/usage/tool-examples.md`
- Modify: `README.md`
- Modify: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/DocsToolExamplesGoldenSuite.scala`
- Create: `mcp/src/test/resources/docs-golden/literal_tokens_table.json` (written by the suite on first run)
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/CallOnceSuite.scala`
- Modify: `regressions/unit-tests.json`

**Interfaces:**
- Consumes: the `literal_tokens` tool (Task 2).
- Produces: documentation only, plus a pinned one-shot run.

**Background.** `docs/usage/tool-examples.md` renders tool calls at build time with `scalasemantic.docs.ToolRunner.runPretty(tool, argsJson)` inside `scala mdoc:passthrough` blocks, and `DocsToolExamplesGoldenSuite` locks each one: its test calls the same tool with the same args and compares against `mcp/src/test/resources/docs-golden/<name>.json`, writing the file on first run. `resolve_references` could not use this — it needs a presentation compiler, which the docs build does not start — so it shows a captured run. `literal_tokens` needs none, so it gets a LIVE example that cannot drift from the code. Passing `source` inline keeps it independent of which files the docs index happens to hold.

- [ ] **Step 1: Write the failing golden test**

Add to `DocsToolExamplesGoldenSuite.scala`, beside the other `assertGolden` tests:

```scala
  test("literal_tokens(a note table)"):
    assertGolden(
      "literal_tokens_table",
      "literal_tokens",
      ujson.Obj(
        "files" -> ujson.Arr(
          ujson.Obj(
            "uri" -> "Notes.scala",
            "source" -> "package demo\nobject Notes:\n  val midi = Map(\"C4\" -> 60, \"D4\" -> 62)\n  def label(n: Int) = s\"note $n\"\n"
          )
        )
      )
    )
```

- [ ] **Step 2: Run it to write the golden, and review it**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.DocsToolExamplesGoldenSuite`
Expected: passes, and writes `mcp/src/test/resources/docs-golden/literal_tokens_table.json` (golden files are written on first run).

Read that file. It must show five literals — `"C4"`, `60`, `"D4"`, `62` enclosed by `val midi`, and `"note "` enclosed by `def label` — with `omitted: 0` and `error: null`. If it shows anything else, the tool is wrong: fix the tool, delete the golden, and re-run. Never commit a golden you have not read.

- [ ] **Step 3: Add the rendered example to the docs page**

In `docs/usage/tool-examples.md`:

1. Add a row to the tool table at the top, directly under the `resolve_references` row:

```markdown
| `literal_tokens` | Every literal in a file's line ranges, normalised, with its enclosing definition |
```

2. Add this section directly after the `### resolve_references` section ends (before the next `###` or `##` heading). The args string MUST be byte-identical to the golden test's args above, or the page and the golden disagree:

````markdown
### literal_tokens

**What it tells you:** every literal in the given line ranges, with a normalised `value` and the
nearest enclosing definition. Syntax only — no presentation compiler, no classpath. It is the fact a
memorisation check needs: a production construct whose literals are largely its tests' own inputs
and expected outputs is a table that learned the tests rather than the problem, and `enclosing` is
what lets a caller judge that per construct.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "literal_tokens",
  """{"files":[{"uri":"Notes.scala","source":"package demo\nobject Notes:\n  val midi = Map(\"C4\" -> 60, \"D4\" -> 62)\n  def label(n: Int) = s\"note $n\"\n"}]}"""))
```

`value` is normalised so equal literals compare equal across files — `0x10` and `16` are both `16`.
Booleans, `null` and `()` are never reported, and no literal is dropped as trivial: deciding that `0`
and `1` carry no signal is the caller's policy, not the tool's.

**Replaces:** grepping for quoted strings and numbers → every literal with its owner, in one call.
````

- [ ] **Step 4: Update the reference table and the README**

In `docs/reference/tools.md`, add a row to the `## Tools` table directly under the `resolve_references` row:

```markdown
| `literal_tokens` | Every literal in the given line ranges, normalised, with its enclosing definition — no classpath needed. |
```

In `README.md`, in the tool list line that contains `` `resolve_references` ``, insert `` · `literal_tokens` `` directly after `` `resolve_references` ``.

- [ ] **Step 5: Pin the one-shot call**

The harness reaches this tool through `scalasemantic-mcp call literal_tokens '<json>' <root>`, and a stale or broken one-shot path has shipped silently before. Add to `CallOnceSuite.scala`, modelled on its existing `resolve_references` test (read that test first and use the same call helper it uses):

```scala
  test("call literal_tokens answers one-shot, without a classpath") {
    val args = ujson.Obj(
      "files" -> ujson.Arr(ujson.Obj("uri" -> "A.scala", "source" -> "package p\nobject O:\n  val x = 42\n"))
    )
    val out = Mcp.callOnce(root, None, "literal_tokens", args)
    val json = out.fold(err => fail(s"call failed: $err"), identity)
    assertEquals(json("files")(0)("literals")(0)("value").str, "42")
  }
```

If `Mcp.callOnce`'s signature or the suite's `root` differs from this, match what the existing `resolve_references` test in the same suite does — the requirement is that the one-shot path answers `literal_tokens` with **no classpath**, which `resolve_references` cannot do.

- [ ] **Step 6: Run the gates**

Register the two new test names (`DocsToolExamplesGoldenSuite.literal_tokens(a note table)`, `CallOnceSuite.call literal_tokens answers one-shot, without a classpath`) in `regressions/unit-tests.json` in sorted order.

Run: `./mill __.test` → SUCCESS. `./mill __.checkFormat` → SUCCESS. Build the docs the way the project builds them (check `build.mill` for the `docs` module's mdoc task, e.g. `./mill docs.mdoc`) and confirm the `literal_tokens` section renders the same JSON as the golden file.

- [ ] **Step 7: Commit**

```bash
git add docs/reference/tools.md docs/usage/tool-examples.md README.md \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/DocsToolExamplesGoldenSuite.scala \
        mcp/src/test/resources/docs-golden/literal_tokens_table.json \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/CallOnceSuite.scala \
        regressions/unit-tests.json
git commit -F - <<'MSG'
docs+mcp: literal_tokens documented with a live example, and its one-shot call pinned

Unlike resolve_references, this tool needs no presentation compiler, so its docs example is rendered
at build time and locked by a golden instead of being a captured run that can drift. The one-shot
`call` path is pinned too, answering without a classpath: that path is how the harness reaches the
tool, and a broken one-shot mode has shipped silently before.

Claude-Session: https://claude.ai/code/session_01TEcqcxKmBFZQupRykQoDMX
MSG
```

---

## After the last task

Install the new build so the harness can reach it: `./mill installLocal --skip-clients`. Then confirm the installed launcher answers the new tool, from any directory:

```bash
scalasemantic-mcp call literal_tokens \
  '{"files":[{"uri":"A.scala","source":"package p\nobject O:\n  val x = 42\n"}]}' "$PWD"
```

Expected: JSON on stdout with one literal, `"value": "42"`, and exit 0 — no classpath involved.
