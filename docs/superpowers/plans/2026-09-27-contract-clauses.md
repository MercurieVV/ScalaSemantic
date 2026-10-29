# contract_clauses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A syntax-only MCP tool, `contract_clauses`, that lists every `def`'s Stainless contract clauses (`require`, `ensuring`, `decreases`) with a formatting-insensitive `shape`, so a harness can detect an agent editing a human-owned contract.

**Architecture:** A new analysis object `ContractClauses` parses a file with scalameta (Scala 3 dialect), walks every `Defn.Def`, peels an `ensuring` (infix or `.ensuring`) off the body, collects top-level `require(...)`/`decreases(...)` statements of the body block, and hashes each clause's `.structure`. A new tool in `McpTools` exposes it with the same `files` argument shape and per-file `error` convention as `literal_tokens`. Docs get a live, golden-locked example and the one-shot `call` path is pinned.

**Tech Stack:** Scala 3, scalameta 4.17.0, upickle, munit, Mill (`./mill`), mdoc docs.

**Spec:** `/Users/viktorskalinins/IdeaProjects/my/scala-coding-guidelines/docs/superpowers/specs/2026-09-27-stainless-vericoding-design.md` — §1 item 1 and §4 (ScalaSemanticMCP half).

## Global Constraints

- Syntax only: no presentation compiler, no classpath — the tool answers wherever the file parses.
- Input: `files` = array of `{uri: string, source?: string}`; `source` overrides the on-disk file. An empty `files` list is a tool error naming the argument.
- Output: `files` holds `{uri, defs, error}` per requested file; `error` is null on success and names the problem (no source / does not parse) with `defs` empty otherwise — a per-file error never fails the whole call.
- Per def: `key` = enclosing owner path + def name, e.g. `PureKernels.toPcm16`; when the same key occurs more than once in one file (overloads), each gets its parameter names appended: `PureKernels.f(a,b)`. `line` (0-based start line of the def), `requires` (list of clauses, source order), `ensuring` (clause or null), `decreases` (clause or null).
- A clause is `{text, shape}`: `text` = the clause's source slice; `shape` = first 16 hex chars of the SHA-256 of the clause term's scalameta `.structure` — reformatting never changes it, any change to the clause's syntax tree does.
- Only defs with at least one clause are reported.
- Tool count goes from 30 to 31 (both assertions in `McpSuite`).
- New test names are registered in `regressions/unit-tests.json`, sorted.
- Commits end with `Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU`; never a `Co-Authored-By` trailer.
- Gates before each commit: `./mill __.test` → SUCCESS and `./mill __.checkFormat` → SUCCESS.

## Review Focus

1. A contract reformatted (braces ↔ braceless, one line ↔ several, extra spaces) must keep every `shape` identical — pinned in Task 1 (`formatting does not change shape`).
2. `ensuring` written infix (`{…} ensuring (r => …)`), as a method call (`{…}.ensuring(r => …)`), or with a block argument (`ensuring { r => … }`) must all be extracted; the infix and method forms of the same postcondition must have the same `shape` — pinned in Task 1.
3. A local `def` inside another `def`, and defs inside nested objects/classes, must get distinct owner-path keys — pinned in Task 1 (`nested owners form the key`).
4. Overloads in one file must not collide on `key` — pinned in Task 1 (`overloads get parameter names`).
5. One unparseable or missing file must not hide the answers for the others — pinned in Task 2 (`a missing file is a per-file error…`).

---

### Task 1: `ContractClauses` — extraction and shape

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/ContractResult.scala`
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/ContractClauses.scala`
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/ContractClausesSuite.scala`
- Modify: `regressions/unit-tests.json`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `model.Clause(text: String, shape: String)`, `model.Contract(key: String, line: Int, requires: List[Clause], ensuring: Option[Clause], decreases: Option[Clause])`, `model.FileContracts(uri: String, defs: List[Contract], error: Option[String])`, `model.ContractResult(files: List[FileContracts])` — all `derives ReadWriter`.
  - `ContractClauses.in(text: String): Option[List[Contract]]` (`None` when the text does not parse) and `ContractClauses.forFile(uri: String, text: String): FileContracts`.

- [ ] **Step 1: Write the model**

`ContractResult.scala`:

```scala
package com.github.mercurievv.scalasemantic.model

import upickle.default.*

/** One contract clause (spec 2026-09-27-stainless-vericoding-design §1): its source `text`, and a
  * `shape` — a hash of the clause's syntax tree — so reformatting never reads as a change while any
  * edit to the clause itself does.
  */
final case class Clause(text: String, shape: String) derives ReadWriter

/** The Stainless contract of one `def`. `key` is the enclosing owner path plus the def name
  * (`PureKernels.toPcm16`); when overloads share a key within one file, each carries its parameter
  * names (`PureKernels.f(a,b)`). `line` is the def's 0-based start line.
  */
final case class Contract(
    key: String,
    line: Int,
    requires: List[Clause],
    ensuring: Option[Clause],
    decreases: Option[Clause]
) derives ReadWriter

/** Every contracted def of one file, in source order. `error` is set — with `defs` empty — when the
  * file could not be read or parsed: no contracts and a syntax error are different answers.
  */
final case class FileContracts(uri: String, defs: List[Contract], error: Option[String])
    derives ReadWriter

/** The wire result: one entry per file asked about. */
final case class ContractResult(files: List[FileContracts]) derives ReadWriter
```

- [ ] **Step 2: Write the failing suite**

`ContractClausesSuite.scala`:

```scala
package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.Contract

class ContractClausesSuite extends munit.FunSuite:

  private def defs(src: String): List[Contract] =
    ContractClauses.in(src).getOrElse(fail("did not parse"))

  private def one(src: String): Contract =
    defs(src) match
      case List(c) => c
      case other   => fail(s"expected one contracted def, got $other")

  test("require, infix ensuring and decreases are extracted with their text") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(x >= 0)
        |    decreases(x)
        |    x + 1
        |  } ensuring (r => r > x)
        |""".stripMargin
    val c = one(src)
    assertEquals(c.key, "K.f")
    assertEquals(c.line, 2)
    assertEquals(c.requires.map(_.text), List("require(x >= 0)"))
    assertEquals(c.decreases.map(_.text), Some("decreases(x)"))
    assert(c.ensuring.exists(_.text.contains("r => r > x")), c.toString)
  }

  test("several requires are kept in source order") {
    val src =
      """package p
        |object K:
        |  def f(a: Int, b: Int): Int = {
        |    require(a > 0)
        |    require(b > 0)
        |    a + b
        |  }
        |""".stripMargin
    assertEquals(one(src).requires.map(_.text), List("require(a > 0)", "require(b > 0)"))
  }

  test("method-call ensuring has the same shape as infix ensuring") {
    val infix =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x
        |  } ensuring (r => r == x)
        |""".stripMargin
    val call =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x
        |  }.ensuring(r => r == x)
        |""".stripMargin
    assertEquals(one(infix).ensuring.map(_.shape), one(call).ensuring.map(_.shape))
    assert(one(call).ensuring.isDefined)
  }

  test("a block-argument ensuring is extracted") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x
        |  } ensuring { r => r == x }
        |""".stripMargin
    assert(one(src).ensuring.exists(_.text.contains("r == x")), one(src).toString)
  }

  test("formatting does not change shape") {
    val compact =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x>=0); x } ensuring (r => r>=0)
        |""".stripMargin
    val spread =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(  x >= 0  )
        |    x
        |  } ensuring (r =>
        |    r >= 0)
        |""".stripMargin
    val a = one(compact)
    val b = one(spread)
    assertEquals(a.requires.map(_.shape), b.requires.map(_.shape))
    assertEquals(a.ensuring.map(_.shape), b.ensuring.map(_.shape))
  }

  test("a semantic change to a clause changes its shape") {
    def src(bound: String) =
      s"""package p
         |object K:
         |  def f(x: Int): Int = {
         |    require(x >= $bound)
         |    x
         |  }
         |""".stripMargin
    assertNotEquals(one(src("0")).requires.map(_.shape), one(src("1")).requires.map(_.shape))
  }

  test("a def without clauses is not reported") {
    val src =
      """package p
        |object K:
        |  def plain(x: Int): Int = x + 1
        |  def checked(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assertEquals(defs(src).map(_.key), List("K.checked"))
  }

  test("a require nested inside an expression is not a contract clause") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    val y = if x > 0 then { require(x < 10); x } else 0
        |    y
        |  }
        |""".stripMargin
    assertEquals(defs(src), Nil)
  }

  test("nested owners form the key") {
    val src =
      """package p
        |object Outer:
        |  class Inner:
        |    def g(x: Int): Int = { require(x > 0); x }
        |  def f(x: Int): Int =
        |    def local(y: Int): Int = { require(y > 0); y }
        |    local(x)
        |""".stripMargin
    assertEquals(defs(src).map(_.key).sorted, List("Outer.Inner.g", "Outer.f.local"))
  }

  test("overloads get parameter names") {
    val src =
      """package p
        |object K:
        |  def f(a: Int): Int = { require(a > 0); a }
        |  def f(a: Int, b: Int): Int = { require(a > b); a }
        |  def g(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assertEquals(defs(src).map(_.key), List("K.f(a)", "K.f(a,b)", "K.g"))
  }

  test("a top-level def has no owner prefix") {
    val src =
      """package p
        |def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assertEquals(one(src).key, "f")
  }

  test("shape is 16 lowercase hex characters") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assert(one(src).requires.forall(_.shape.matches("[0-9a-f]{16}")), one(src).toString)
  }

  test("text that does not parse is None, and forFile names it") {
    assertEquals(ContractClauses.in("object O { def = }"), None)
    val f = ContractClauses.forFile("A.scala", "object O { def = }")
    assertEquals(f.defs, Nil)
    assert(f.error.exists(_.contains("does not parse")), f.toString)
  }
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.ContractClausesSuite`
Expected: compilation FAILS with `Not found: ContractClauses` (the model compiles; the object does not exist yet).

- [ ] **Step 4: Write `ContractClauses`**

```scala
package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.{Clause, Contract, FileContracts}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.meta.*
import scala.util.Try

/** Every `def`'s Stainless contract — its `require`, `ensuring` and `decreases` clauses — with a
  * formatting-insensitive `shape` (spec 2026-09-27-stainless-vericoding-design §1). Syntax only,
  * the sibling of [[LiteralTokens]]: no presentation compiler, no classpath.
  *
  * What counts as a clause is Stainless's own placement: `ensuring` is the outermost call on the
  * def's body (infix or method form); `require` and `decreases` are TOP-LEVEL statements of the
  * body block. A `require` nested inside an expression is ordinary code, not a contract.
  */
object ContractClauses:

  /** The contracted defs of `text`, in source order; `None` when the text does not parse. */
  def in(text: String): Option[List[Contract]] =
    Try(dialects.Scala3(text).parse[Source].get).toOption.map { tree =>
      val raw = tree.collect { case d: Defn.Def => contractOf(d) }.flatten
      val collisions = raw.groupBy(_._1.key).collect { case (k, vs) if vs.size > 1 => k }.toSet
      raw.map { (c, params) =>
        if collisions.contains(c.key) then c.copy(key = s"${c.key}(${params.mkString(",")})")
        else c
      }
    }

  /** [[in]] for one file, with a parse failure turned into `error`. */
  def forFile(uri: String, text: String): FileContracts =
    in(text) match
      case None       => FileContracts(uri, Nil, Some(s"$uri does not parse as Scala 3"))
      case Some(defs) => FileContracts(uri, defs, None)

  /** The def's contract plus its parameter names (used only to disambiguate overloads), or `None`
    * when it carries no clause.
    */
  private def contractOf(d: Defn.Def): Option[(Contract, List[String])] =
    val (body, ensuring) = peelEnsuring(d.body)
    val stats = body match
      case b: Term.Block => b.stats
      case _             => Nil
    val requires = stats.collect { case a: Term.Apply if isCall(a, "require") => clause(a) }
    val decreases = stats.collectFirst { case a: Term.Apply if isCall(a, "decreases") => clause(a) }
    val ens = ensuring.map(clause)
    if requires.isEmpty && ens.isEmpty && decreases.isEmpty then None
    else
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      Some(Contract(keyOf(d), d.pos.startLine, requires, ens, decreases) -> params)

  /** `body ensuring post`, `body.ensuring(post)` → (body, Some(post)); anything else → (t, None). */
  private def peelEnsuring(t: Term): (Term, Option[Term]) =
    t match
      case ai: Term.ApplyInfix if ai.op.value == "ensuring" && ai.argClause.values.size == 1 =>
        (ai.lhs, ai.argClause.values.headOption)
      case a: Term.Apply =>
        a.fun match
          case Term.Select(qual, Term.Name("ensuring")) if a.argClause.values.size == 1 =>
            (qual, a.argClause.values.headOption)
          case _ => (t, None)
      case _ => (t, None)

  private def isCall(a: Term.Apply, name: String): Boolean =
    a.fun match
      case Term.Name(n) => n == name
      case _            => false

  private def clause(t: Tree): Clause = Clause(t.pos.text, shapeOf(t))

  /** First 16 hex chars of SHA-256 over the tree's `.structure`: positions, whitespace and
    * comments are not part of `.structure`, so reformatting keeps the shape.
    */
  private def shapeOf(t: Tree): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(t.structure.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Enclosing object/class/trait/enum/def names, outermost first, then the def's own name. */
  private def keyOf(d: Defn.Def): String =
    val owners = Iterator
      .iterate(d.parent)(_.flatMap(_.parent))
      .takeWhile(_.isDefined)
      .flatten
      .collect {
        case o: Defn.Object => o.name.value
        case c: Defn.Class  => c.name.value
        case t: Defn.Trait  => t.name.value
        case e: Defn.Enum   => e.name.value
        case f: Defn.Def    => f.name.value
      }
      .toList
      .reverse
    (owners :+ d.name.value).mkString(".")
```

Close the suite's class body if your formatter requires it (Scala 3 indentation needs none). If scalameta 4.17's API names differ (`paramClauseGroups`, `argClause.values`), use the equivalents the compiler suggests and note the change in the report — the behaviour the tests pin is the requirement.

- [ ] **Step 5: Run the suite and watch it pass**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.ContractClausesSuite`
Expected: 13 tests pass. If `formatting does not change shape` fails, print both `.structure` strings and find the difference before touching the test — the requirement is that whitespace-only edits keep the shape.

- [ ] **Step 6: Register the tests, run the gates**

Add the 13 test names to `regressions/unit-tests.json`, sorted, as `"com.github.mercurievv.scalasemantic.analysis.ContractClausesSuite.<test name>"`.

Run: `./mill __.test` → SUCCESS. `./mill __.checkFormat` → SUCCESS (run `./mill __.reformat` first if needed).

- [ ] **Step 7: Commit**

```bash
git add analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/ContractResult.scala \
        analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/ContractClauses.scala \
        analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/ContractClausesSuite.scala \
        regressions/unit-tests.json
git commit -F - <<'MSG'
analysis: ContractClauses — every def's require/ensuring/decreases with a formatting-proof shape

The fact a harness needs to keep a human-owned Stainless contract from being edited by the agent
that writes the body: clauses are found where Stainless puts them (ensuring outermost, require and
decreases as top-level body statements), and each carries a hash of its syntax tree, so reformatting
never reads as a change and any edit to the clause does.

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU
MSG
```

---

### Task 2: The `contract_clauses` MCP tool

**Files:**
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala` (a new `toolDef` directly after the `literal_tokens` one; imports beside the `LiteralTokens` import at line 5)
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpSuite.scala`
- Modify: `regressions/unit-tests.json`

**Interfaces:**
- Consumes: `ContractClauses.forFile`, `FileContracts`, `ContractResult` (Task 1); the existing `argUri`, `readFileText`, `root`, `error`, `tool`, `toolDef` helpers in `McpTools`.
- Produces: tool `contract_clauses` with argument `files` = `[{uri, source?}]`, result `ContractResult` JSON.

- [ ] **Step 1: Write the failing tests** — add to `McpSuite.scala` beside the `literal_tokens` tests (the suite's private `call(name, args)` and `callResponse(name, args)` helpers exist):

```scala
  test("contract_clauses answers from `source` without a presentation compiler") {
    val src = "package p\nobject K:\n  def f(x: Int): Int = { require(x > 0); x } ensuring (r => r > 0)\n"
    val r = call(
      "contract_clauses",
      ujson.Obj("files" -> ujson.Arr(ujson.Obj("uri" -> "K.scala", "source" -> src)))
    )
    val file = r("files")(0)
    assertEquals(file("uri").str, "K.scala")
    assert(file("error").isNull, file.toString)
    val d = file("defs")(0)
    assertEquals(d("key").str, "K.f")
    assertEquals(d("requires")(0)("text").str, "require(x > 0)")
    assert(d("ensuring")("text").str.contains("r => r > 0"), d.toString)
    assert(d("decreases").isNull, d.toString)
  }

  test("contract_clauses: a missing file is a per-file error, the others still answer") {
    val r = call(
      "contract_clauses",
      ujson.Obj(
        "files" -> ujson.Arr(
          ujson.Obj("uri" -> "does/not/Exist.scala"),
          ujson.Obj("uri" -> "B.scala", "source" -> "package p\nobject B:\n  def g(x: Int): Int = { require(x > 1); x }\n")
        )
      )
    )
    assertEquals(r("files")(0)("defs").arr.size, 0)
    assert(r("files")(0)("error").str.contains("no source"), r.toString)
    assertEquals(r("files")(1)("defs")(0)("key").str, "B.g")
  }

  test("contract_clauses: a file that does not parse is a per-file error") {
    val r = call(
      "contract_clauses",
      ujson.Obj("files" -> ujson.Arr(ujson.Obj("uri" -> "A.scala", "source" -> "object O { def = }")))
    )
    assert(r("files")(0)("error").str.contains("does not parse"), r.toString)
  }

  test("contract_clauses with an empty files list is an error") {
    val text = callResponse("contract_clauses", ujson.Obj("files" -> ujson.Arr()))
      .getOrElse(fail("no response"))("result")("content")(0)("text")
      .str
    assert(text.contains("`files` must hold at least one"), text)
  }
```

Also change both tool-count assertions from `30` to `31` (`tools/list exposes all tools with schemas` at ~line 144, and the `process maps a request stream…` test at ~line 1269), and add `assert(names.contains("contract_clauses"), names.toString)` beside the `literal_tokens` one.

- [ ] **Step 2: Run and watch them fail**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpSuite`
Expected: the four new tests FAIL (unknown tool) and both counts FAIL (`30` != `31`).

- [ ] **Step 3: Add the tool** — directly after the `literal_tokens` `toolDef(...)`:

```scala
      toolDef(
        tool(
          "contract_clauses",
          "Every def's Stainless CONTRACT — its `require`, `ensuring` and `decreases` clauses — the " +
            "syntax fact behind a guard that keeps a human-owned contract from being edited by the " +
            "agent writing the body. Syntax only: no presentation compiler and no classpath. Clauses " +
            "are found where Stainless puts them: `ensuring` as the outermost call on the body (infix " +
            "or `.ensuring(…)`), `require`/`decreases` as top-level statements of the body block. " +
            "Each def carries `key` (owner path + name, e.g. `PureKernels.toPcm16`; overloads in one " +
            "file get their parameter names, `K.f(a,b)`), `line` (0-based), `requires` (list), " +
            "`ensuring` and `decreases` (clause or null). A clause is {text, shape}: `shape` hashes " +
            "the clause's syntax tree, so reformatting keeps it and any edit to the clause changes " +
            "it. Only defs with at least one clause are listed. Pass `source` for the buffer's text, " +
            "else the on-disk file is read. OUTPUT: `files` holds {uri, defs, error} per requested " +
            "file; `error` is null on success, and names the problem (no source, does not parse) " +
            "with `defs` empty otherwise.",
          List(("files", "array", "objects: {uri: string, source?: string}")),
          List("files")
        ) { a =>
          val entries = a.obj.get("files").toList.flatMap(_.arr)
          if entries.isEmpty then error("contract_clauses: `files` must hold at least one {uri} object")
          else
            val answers = entries.map { entry =>
              val uri = argUri(entry, "uri")
              val text =
                entry.obj.get("source").map(_.str).orElse(readFileText(root.resolve(uri.value)))
              text match
                case None =>
                  FileContracts(
                    uri.value,
                    Nil,
                    Some(s"no source: pass `source` or make ${uri.value} exist on disk")
                  )
                case Some(t) => ContractClauses.forFile(uri.value, t)
            }
            upickle.default.writeJs(ContractResult(answers))
        }
      ),
```

Imports: add `com.github.mercurievv.scalasemantic.analysis.ContractClauses` and `com.github.mercurievv.scalasemantic.model.{ContractResult, FileContracts}` beside the existing `LiteralTokens` / `LiteralResult` imports.

- [ ] **Step 4: Run and watch them pass**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpSuite`
Expected: all pass, counts at 31.

- [ ] **Step 5: Register, gates**

Add the four new test names to `regressions/unit-tests.json` sorted, as `"com.github.mercurievv.scalasemantic.mcp.McpSuite.<test name>"`. Run `./mill __.test` → SUCCESS, `./mill __.checkFormat` → SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpSuite.scala \
        regressions/unit-tests.json
git commit -F - <<'MSG'
mcp: contract_clauses tool — Stainless contracts per def, no presentation compiler needed

Same `files` shape and per-file error convention as literal_tokens, so a harness reaches both the
same way: one missing or unparseable file never loses the other files' answers.

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU
MSG
```

---

### Task 3: Docs, the rendered example, and the one-shot call

**Files:**
- Modify: `docs/reference/tools.md` (row under the `literal_tokens` row, line ~18)
- Modify: `docs/usage/tool-examples.md` (table row + section after `### literal_tokens`)
- Modify: `README.md` (line ~34 tool list: insert `` · `contract_clauses` `` after `` `literal_tokens` ``)
- Modify: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/DocsToolExamplesGoldenSuite.scala`
- Create: `mcp/src/test/resources/docs-golden/contract_clauses_kernel.json` (written by the suite on first run)
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/CallOnceSuite.scala`
- Modify: `regressions/unit-tests.json`

**Interfaces:**
- Consumes: the `contract_clauses` tool (Task 2).
- Produces: docs; a pinned one-shot run.

- [ ] **Step 1: Failing golden test** — add beside the `literal_tokens_table` test in `DocsToolExamplesGoldenSuite.scala`:

```scala
  test("contract_clauses(a pure kernel)"):
    assertGolden(
      "contract_clauses_kernel",
      "contract_clauses",
      ujson.Obj(
        "files" -> ujson.Arr(
          ujson.Obj(
            "uri" -> "PureKernels.scala",
            "source" -> "package demo\nimport stainless.lang.*\nobject PureKernels:\n  def framesToNanos(frames: Int, sampleRate: Int): Long = {\n    require(frames >= 0 && sampleRate > 0)\n    frames.toLong * 1000000000L / sampleRate\n  } ensuring (r => r >= 0)\n"
          )
        )
      )
    )
```

- [ ] **Step 2: Run it, then read the golden**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.DocsToolExamplesGoldenSuite`
Expected: passes and writes `mcp/src/test/resources/docs-golden/contract_clauses_kernel.json`. Read it: one def, `key` `PureKernels.framesToNanos`, `line` 3, one `requires` with text `require(frames >= 0 && sampleRate > 0)`, `ensuring` text containing `r => r >= 0` (with or without the parentheses, as scalameta positions it), `decreases` null, `error` null. Anything else means the tool is wrong: fix, delete the golden, re-run. Never commit a golden you have not read.

- [ ] **Step 3: Docs page** — in `docs/usage/tool-examples.md` add a table row directly under the `literal_tokens` row:

```markdown
| `contract_clauses` | Every def's Stainless contract (require/ensuring/decreases) with a formatting-proof shape |
```

and, directly after the `### literal_tokens` section ends, this section (args byte-identical to the golden test's):

````markdown
### contract_clauses

**What it tells you:** each `def`'s Stainless contract — `require`, `ensuring`, `decreases` — with a
`shape` per clause that hashes its syntax tree. Syntax only. It is the fact a harness needs to keep a
human-owned contract from being rewritten by the agent that fills in the body: reformatting keeps
every shape, and any edit to a clause changes it.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "contract_clauses",
  """{"files":[{"uri":"PureKernels.scala","source":"package demo\nimport stainless.lang.*\nobject PureKernels:\n  def framesToNanos(frames: Int, sampleRate: Int): Long = {\n    require(frames >= 0 && sampleRate > 0)\n    frames.toLong * 1000000000L / sampleRate\n  } ensuring (r => r >= 0)\n"}]}"""))
```

**Replaces:** grepping for `require`/`ensuring` lines → every clause per def, formatting-proof, in one call.
````

- [ ] **Step 4: Reference table and README** — `docs/reference/tools.md`, under the `literal_tokens` row:

```markdown
| `contract_clauses` | Every def's Stainless contract (require/ensuring/decreases) with a formatting-proof shape — no classpath needed. |
```

`README.md` line ~34: insert `` · `contract_clauses` `` directly after `` `literal_tokens` ``.

- [ ] **Step 5: Pin the one-shot call** — add to `CallOnceSuite.scala` beside the `literal_tokens` one-shot test:

```scala
  test("call contract_clauses answers one-shot, without a classpath") {
    val args = ujson.Obj(
      "files" -> ujson.Arr(
        ujson.Obj("uri" -> "K.scala", "source" -> "package p\nobject K:\n  def f(x: Int): Int = { require(x > 0); x }\n")
      )
    )
    val json = Mcp
      .callOnce(".", None, "contract_clauses", args)
      .fold(err => fail(s"call failed: $err"), identity)
    assertEquals(json("files")(0)("defs")(0)("key").str, "K.f")
  }
```

- [ ] **Step 6: Register, gates, docs build**

Register `DocsToolExamplesGoldenSuite.contract_clauses(a pure kernel)` and `CallOnceSuite.call contract_clauses answers one-shot, without a classpath` in `regressions/unit-tests.json`, sorted. Run `./mill __.test` → SUCCESS, `./mill __.checkFormat` → SUCCESS. Build the docs with the `docs` module's mdoc task (see `build.mill` ~line 525; e.g. `./mill docs.run` or the task the module defines) and confirm the `contract_clauses` section renders the same JSON as the golden.

- [ ] **Step 7: Commit**

```bash
git add docs/reference/tools.md docs/usage/tool-examples.md README.md \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/DocsToolExamplesGoldenSuite.scala \
        mcp/src/test/resources/docs-golden/contract_clauses_kernel.json \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/CallOnceSuite.scala \
        regressions/unit-tests.json
git commit -F - <<'MSG'
docs+mcp: contract_clauses documented with a live example, and its one-shot call pinned

The harness reaches this tool through `scalasemantic-mcp call`, answering without a classpath; the
docs example is rendered at build time and locked by a golden so it cannot drift from the code.

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU
MSG
```

---

## After the last task (human step)

`./mill installLocal --skip-clients` replaces the jar behind the running MCP server, so the human runs it. Then confirm the installed launcher answers, from any directory:

```bash
scalasemantic-mcp call contract_clauses \
  '{"files":[{"uri":"K.scala","source":"package p\nobject K:\n  def f(x: Int): Int = { require(x > 0); x }\n"}]}' .
```

Expected: JSON with `files[0].defs[0].key == "K.f"`.

## Self-review notes

- Spec §1 item 1 → Tasks 1–2 (key, line, requires/ensuring/decreases, text+shape, per-file error, one-shot) ; §4 ScalaSemanticMCP bullet → Task 1 suite (extraction, shape stable/unstable, multi-line, infix, decreases, parse error), Task 3 (docs golden, one-shot).
- Ruling recorded here: `shape` covers the whole `require(...)`/`decreases(...)` call, message argument included — a changed message blocks too (conservative; the human can re-baseline). The `ensuring` shape covers its argument (the postcondition lambda), so infix and method forms agree.
- Names consistent across tasks: `ContractClauses.in/forFile`, `Clause`, `Contract`, `FileContracts`, `ContractResult`, tool `contract_clauses`.
