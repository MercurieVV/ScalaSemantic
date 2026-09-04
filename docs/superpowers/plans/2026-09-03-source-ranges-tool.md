# Source Ranges Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new MCP tool, `source_ranges`, that returns compiler-elaborated `detail=full`
annotations for an explicit, caller-chosen set of line spans across one or more files/symbols in a
single call — the deep-dive companion to a first, terse `annotated_source` skim.

**Architecture:** A pure parser (`RangeSelector`, `analysis` module) turns a compact selector string
(`target[N-M;...];target2[N-M]`) into a typed `List[Entry]`. The `mcp` module's new `source_ranges`
tool resolves each `target` to a `DocumentUri` (a literal file path, or a symbol/FQN resolved via
the existing `resolveSymbolOrFqn`), converts each 1-based inclusive `LineRange` to a `SourceRange`,
and renders it through the existing `renderRange` helper already shared by `symbol_source` and
`source_around_position` — so all existing rendering machinery (formats, `detail=full`, sentinel,
symbols legend) is reused unchanged, not reimplemented.

**Tech Stack:** Scala 3.8.4, Mill, MUnit (+ ScalaCheck), upickle/ujson — same as the rest of the
repo; no new dependency.

**Spec:** This document (no separate spec — the shape was agreed in chat: `target[N-M;...]`
entries separated by `;`, `target` = file path (optionally without extension) or dotted FQN/symbol,
ranges are 1-based inclusive).

## Global Constraints

- No Wartremover violations: no `var`, `.asInstanceOf`, `null`, `throw` (use `error(...)` which
  itself throws internally, already whitelisted elsewhere in `McpTools.scala`), no `List`/`Seq`
  `:+`/`.head`/indexing — use `++ List(x)` / `.headOption` / `zipWithIndex` instead (see
  `SourceSentinel.scala` for the established pattern in this repo).
- Golden tests, once regenerated and reviewed, are locked and are the source of truth for exact
  output shape — do not hand-edit them; delete + rerun twice to regenerate.
- Reuse `renderRange`, `resolveSymbolOrFqn`, `SourceRange.from`, `DocumentUri.from`,
  `SourceView.params`, `jobj`/`opt`/`strs`/`error`/`notFound` — do not duplicate their logic.

---

### Task 1: `RangeSelector` — pure selector-string parser

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelector.scala`
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelectorSuite.scala`
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelectorPropertySuite.scala`

**Interfaces:**
- Produces: `RangeSelector.LineRange(startLine: Int, endLine: Int)` (1-based, inclusive),
  `RangeSelector.Entry(target: String, ranges: List[LineRange])`,
  `RangeSelector.parse(query: String): Either[String, List[Entry]]`,
  `RangeSelector.looksLikeFileTarget(target: String): Boolean`. Task 2 consumes all four.

- [ ] **Step 1: Write the failing tests**

```scala
package com.github.mercurievv.scalasemantic.analysis

class RangeSelectorSuite extends munit.FunSuite:

  test("parses a single target with a single range"):
    assertEquals(
      RangeSelector.parse("Foo.scala[10-20]"),
      Right(List(RangeSelector.Entry("Foo.scala", List(RangeSelector.LineRange(10, 20)))))
    )

  test("parses a single target with multiple ranges"):
    assertEquals(
      RangeSelector.parse("scala.String[10-20;30-40]"),
      Right(
        List(
          RangeSelector.Entry(
            "scala.String",
            List(RangeSelector.LineRange(10, 20), RangeSelector.LineRange(30, 40))
          )
        )
      )
    )

  test("parses multiple targets separated by ';' outside brackets"):
    assertEquals(
      RangeSelector.parse("scala.String[10-20;30-40];com/mercurievv/Olo.scala[50-55]"),
      Right(
        List(
          RangeSelector.Entry(
            "scala.String",
            List(RangeSelector.LineRange(10, 20), RangeSelector.LineRange(30, 40))
          ),
          RangeSelector.Entry("com/mercurievv/Olo.scala", List(RangeSelector.LineRange(50, 55)))
        )
      )
    )

  test("rejects empty input"):
    assert(RangeSelector.parse("").isLeft)
    assert(RangeSelector.parse("   ").isLeft)

  test("rejects a range with end before start"):
    assert(RangeSelector.parse("Foo.scala[20-10]").isLeft)

  test("rejects malformed range syntax"):
    assert(RangeSelector.parse("Foo.scala[abc]").isLeft)

  test("rejects text outside any target[...] entry"):
    assert(RangeSelector.parse("Foo.scala[10-20] garbage").isLeft)

  test("rejects an entry with an empty target"):
    assert(RangeSelector.parse("[10-20]").isLeft)

  test("looksLikeFileTarget: true for a path with a slash"):
    assert(RangeSelector.looksLikeFileTarget("com/mercurievv/Olo.scala"))

  test("looksLikeFileTarget: true for a bare .scala filename"):
    assert(RangeSelector.looksLikeFileTarget("Foo.scala"))

  test("looksLikeFileTarget: false for a dotted FQN"):
    assert(!RangeSelector.looksLikeFileTarget("scala.String"))
```

```scala
package com.github.mercurievv.scalasemantic.analysis

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class RangeSelectorPropertySuite extends munit.ScalaCheckSuite:

  private val targetGen: Gen[String] =
    Gen.oneOf(
      Gen.const("Foo.scala"),
      Gen.const("com/mercurievv/Olo.scala"),
      Gen.const("scala.String"),
      Gen.const("com.example.Bar")
    )

  private val rangeGen: Gen[(Int, Int)] =
    for
      start <- Gen.choose(1, 500)
      len   <- Gen.choose(0, 50)
    yield (start, start + len)

  private val entryGen: Gen[String] =
    for
      target <- targetGen
      ranges <- Gen.nonEmptyListOf(rangeGen)
    yield s"$target[${ranges.map((s, e) => s"$s-$e").mkString(";")}]"

  property("any query built from the grammar round-trips through parse as Right with the same shape"):
    forAll(Gen.nonEmptyListOf(entryGen)) { entries =>
      val query = entries.mkString(";")
      RangeSelector.parse(query) match
        case Left(err)     => fail(s"expected Right for '$query', got Left($err)")
        case Right(parsed) => assertEquals(parsed.size, entries.size)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mill analysis.test com.github.mercurievv.scalasemantic.analysis.RangeSelectorSuite`
Expected: FAIL — `RangeSelector` not defined. (Ignore the module's bogus "1 failed, 1 ignored, 0
total" line for filtered runs noted in project memory — run the full `./mill analysis.test` and
check the SUCCESS/FAILED summary line, or grep the suite name, for the trustworthy result.)

- [ ] **Step 3: Write the implementation**

```scala
package com.github.mercurievv.scalasemantic.analysis

/** Parses a compact multi-target, multi-range selector string used by tools that want the
  * compiler's full elaborated detail for a few chosen line spans instead of a whole file — e.g.
  * after skimming a file's terse `annotated_source` output, request `detail=full` for just the
  * lines that need a closer look. Grammar: `entry (";" entry)*` where
  * `entry := target "[" range (";" range)* "]"` and `range := start "-" end` (1-based, inclusive
  * on both ends). `target` is either a file uri (contains "/", or ends in a known source
  * extension) or a dotted FQN/simple name resolved via the symbol index by the caller. Example:
  * `scala.String[10-20;30-40];com/mercurievv/Olo.scala[50-55]`.
  */
object RangeSelector:

  /** One requested line span, 1-based and inclusive on both ends. */
  final case class LineRange(startLine: Int, endLine: Int)

  /** One target (file uri or FQN) with the line spans requested inside it. */
  final case class Entry(target: String, ranges: List[LineRange])

  private val EntryPattern = raw"([^\[\];]+)\[([^\]]*)\]".r
  private val RangePattern = raw"(\d+)-(\d+)".r
  private val FileExtensions = List(".scala", ".sc", ".mill")

  /** True when `target` should be resolved as a literal file path rather than a symbol/FQN: it
    * contains a path separator, or already carries a known source-file extension.
    */
  def looksLikeFileTarget(target: String): Boolean =
    target.contains("/") || FileExtensions.exists(target.endsWith)

  /** Parse the whole selector string, or the first problem found: empty input, text that is not
    * part of any `target[...]` entry, an empty target, or a malformed/inverted range.
    */
  def parse(query: String): Either[String, List[Entry]] =
    val trimmed = query.trim
    if trimmed.isEmpty then Left("empty range selector")
    else
      val matches = EntryPattern.findAllMatchIn(trimmed).toList
      val reconstructed = matches.map(_.matched).mkString(";")
      if matches.isEmpty || reconstructed != trimmed then
        Left(s"could not parse '$query' as 'target[N-M;...]' entries separated by ';'")
      else parseEntries(matches)

  private def parseEntries(
      matches: List[scala.util.matching.Regex.Match]
  ): Either[String, List[Entry]] =
    matches.foldLeft[Either[String, List[Entry]]](Right(Nil)) { (acc, m) =>
      acc.flatMap { entries =>
        val target = m.group(1).trim
        if target.isEmpty then Left(s"empty target in '${m.matched}'")
        else parseRanges(m.group(2)).map(ranges => entries ++ List(Entry(target, ranges)))
      }
    }

  private def parseRanges(raw: String): Either[String, List[LineRange]] =
    val parts = raw.split(";", -1).toList.map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then Left(s"no ranges in '[$raw]'")
    else
      parts.foldLeft[Either[String, List[LineRange]]](Right(Nil)) { (acc, p) =>
        acc.flatMap { ranges =>
          p match
            case RangePattern(s, e) =>
              val start = s.toInt
              val end = e.toInt
              if end < start then Left(s"range '$p' has end before start")
              else Right(ranges ++ List(LineRange(start, end)))
            case other => Left(s"invalid range '$other', expected 'N-M'")
        }
      }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mill analysis.test` (unfiltered — see the filtered-run caveat in Step 2)
Expected: `RangeSelectorSuite` and `RangeSelectorPropertySuite` both pass, 0 failed.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelector.scala \
        analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelectorSuite.scala \
        analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelectorPropertySuite.scala
git commit -m "feat(analysis): add RangeSelector parser for multi-file/multi-range deep-dive queries"
```

---

### Task 2: `source_ranges` MCP tool

**Files:**
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala`
  (`McpToolsGroupD.tools`, appended after `smart_code_duplications`)
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/SourceRangesSuite.scala`

**Interfaces:**
- Consumes: `RangeSelector.parse`, `RangeSelector.Entry`, `RangeSelector.LineRange`,
  `RangeSelector.looksLikeFileTarget` (Task 1); `McpToolsSupport.{resolveSymbolOrFqn, renderRange,
  jobj, opt, strs, error, SourceView}`, `Analyzer.symbolDefinitionRange`,
  `InputTypes.{DocumentUri, SourceRange}` (existing).
- Produces: the `source_ranges` tool, registered in `McpToolsGroupD.tools` alongside the other
  read-only tools — no new public API for later tasks (this plan has only two tasks).

- [ ] **Step 1: Write the failing tests**

```scala
package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.{Files, Path}

/** `source_ranges`: the deep-dive companion to `annotated_source` — given one or more targets each
  * with explicit 1-based inclusive line ranges, render `detail=full` (by default) for just those
  * spans instead of a whole file. File-path targets need no real SemanticDB index; FQN/symbol
  * targets are exercised against this repo's own dogfood index in a second suite.
  */
class SourceRangesSuite extends munit.FunSuite:

  private def toolsIn(root: Path) =
    Mcp.toolsFor(Analyzer(SemanticIndex(Vector.empty)), root)

  private def call(root: Path, args: ujson.Value): ujson.Value =
    toolsIn(root)
      .find(_.name == "source_ranges")
      .getOrElse(fail("no source_ranges"))
      .run(args)

  private def writeFile(root: Path, name: String, content: String): Unit =
    val _ = Files.writeString(root.resolve(name), content)

  test("renders the requested lines of a single file-path target"):
    val root = Files.createTempDirectory("source-ranges-single").nn
    writeFile(root, "Foo.scala", (1 to 30).map(i => s"// line $i").mkString("\n"))
    val res = call(root, ujson.Obj("query" -> "Foo.scala[10-12]"))
    assertEquals(res("results").arr.size, 1)
    val entry = res("results").arr.head
    assertEquals(entry("target").str, "Foo.scala")
    assertEquals(entry("requestedRange").str, "10-12")
    assertEquals(entry("found").bool, true)
    assert(entry("source").str.contains("// line 10"))
    assert(entry("source").str.contains("// line 12"))
    assert(!entry("source").str.contains("// line 9"))
    assert(!entry("source").str.contains("// line 13"))

  test("defaults to detail=full when the caller does not pass a detail arg"):
    val root = Files.createTempDirectory("source-ranges-default-detail").nn
    writeFile(root, "Foo.scala", "object Foo:\n  def bar: Int = 1\n")
    val res = call(root, ujson.Obj("query" -> "Foo.scala[1-2]"))
    val legend = res("results").arr.head("legend").str
    assert(legend.contains("elaborated"), legend)

  test("caller-supplied detail overrides the default"):
    val root = Files.createTempDirectory("source-ranges-explicit-detail").nn
    writeFile(root, "Foo.scala", "object Foo:\n  def bar: Int = 1\n")
    val res = call(root, ujson.Obj("query" -> "Foo.scala[1-2]", "detail" -> "terse"))
    val legend = res("results").arr.head("legend").str
    assert(!legend.contains("elaborated"), legend)

  test("renders multiple ranges of one target and multiple targets in one call"):
    val root = Files.createTempDirectory("source-ranges-multi").nn
    writeFile(root, "Foo.scala", (1 to 30).map(i => s"// line $i").mkString("\n"))
    writeFile(root, "Bar.scala", (1 to 10).map(i => s"// bar $i").mkString("\n"))
    val res =
      call(root, ujson.Obj("query" -> "Foo.scala[1-2;20-21];Bar.scala[5-6]"))
    val results = res("results").arr
    assertEquals(results.size, 3)
    assertEquals(results.map(_("target").str).toList, List("Foo.scala", "Foo.scala", "Bar.scala"))
    assertEquals(
      results.map(_("requestedRange").str).toList,
      List("1-2", "20-21", "5-6")
    )

  test("reports found=false with an error for a target that does not resolve"):
    val root = Files.createTempDirectory("source-ranges-missing").nn
    val res = call(root, ujson.Obj("query" -> "DoesNotExist.scala[1-2]"))
    val entry = res("results").arr.head
    assertEquals(entry("target").str, "DoesNotExist.scala")
    assertEquals(entry("found").bool, false)
    assert(entry("error").str.nonEmpty)

  test("rejects a malformed query with an error rather than a partial result"):
    val root = Files.createTempDirectory("source-ranges-malformed").nn
    intercept[RuntimeException](call(root, ujson.Obj("query" -> "not a valid query")))
```

```scala
package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Paths

/** `source_ranges` resolving a dotted FQN target against this repo's own SemanticDB — dogfooding,
  * same pattern as `McpSuite`/`TokenMetricsSuite`. Structural assertions only (no golden lock):
  * this repo's own source shifts over time, so pin behavior, not exact line content.
  */
class SourceRangesFqnSuite extends munit.FunSuite:

  private val root = Paths.get(".").nn
  private val tools = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
  private def call(args: ujson.Value): ujson.Value =
    tools.find(_.name == "source_ranges").getOrElse(fail("no source_ranges")).run(args)

  test("resolves a dotted FQN target to its defining file and renders the requested range"):
    val res = call(
      ujson.Obj(
        "query" -> "com.github.mercurievv.scalasemantic.analysis.RangeSelector[1-1]"
      )
    )
    val entry = res("results").arr.head
    assertEquals(entry("found").bool, true)
    assertEquals(
      entry("uri").str,
      "analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/RangeSelector.scala"
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mill mcp.test` (unfiltered)
Expected: FAIL to compile / FAIL — no tool named `source_ranges`.

- [ ] **Step 3: Write the implementation**

Add to `McpToolsGroupD.tools`'s `List(...)` in `McpTools.scala`, as the last entry (after the
`smart_code_duplications` `toolDef(...)`, before the closing `)` of the `List(`):

```scala
        ,
        toolDef(
          tool(
            "source_ranges",
            "The deep-dive companion to `annotated_source`: after skimming a file's terse " +
              "annotations, request the compiler's full elaborated detail for just the line spans " +
              "that need a closer look — across one or more files/symbols in a single call, " +
              "instead of one whole-file read per target. `query` is `target[N-M;...];target2[N-M]` " +
              "— `;`-separated entries, each a `target` (a file uri, optionally without its " +
              "extension, or a dotted FQN/symbol resolved the same way `symbol_source` resolves " +
              "one) followed by one or more `;`-separated 1-based inclusive `start-end` line " +
              "ranges in `[...]`. Example: `scala.String[10-20;30-40];com/mercurievv/Olo.scala" +
              "[50-55]`. Renders each range with `detail=full` by default (override via `detail`); " +
              "otherwise identical to `annotated_source` — same `format`/`annotationsOnly`/" +
              "`symbols`/`docs`/`sentinel` args and output shape per range. A target that fails to " +
              "resolve reports `found: false` with an `error`, alongside any targets that did " +
              "resolve — a malformed `query` itself is rejected outright.",
            ("query", "string", "target[N-M;...];target2[N-M] selector — see description") ::
              SourceView.params,
            List("query")
          ) { a =>
            val query = argStr(a, "query")
            RangeSelector.parse(query) match
              case Left(err) => error(s"source_ranges: $err")
              case Right(entries) =>
                val argsWithDefaultDetail =
                  if a.obj.contains("detail") then a
                  else ujson.Obj.from(a.obj.toSeq ++ Seq("detail" -> ujson.Str("full")))
                val results = entries.flatMap { entry =>
                  resolveRangeTarget(az, entry.target) match
                    case None =>
                      List(
                        jobj(
                          Some("target" -> ujson.Str(entry.target)),
                          Some("found" -> ujson.Bool(false)),
                          Some(
                            "error" -> ujson.Str(
                              s"could not resolve target '${entry.target}' to a file or symbol"
                            )
                          )
                        )
                      )
                    case Some(uri) =>
                      entry.ranges.map { lr =>
                        SourceRange.from(lr.startLine - 1, 0, lr.endLine, 0) match
                          case Left(err) =>
                            jobj(
                              Some("target" -> ujson.Str(entry.target)),
                              Some(
                                "requestedRange" -> ujson.Str(s"${lr.startLine}-${lr.endLine}")
                              ),
                              Some("found" -> ujson.Bool(false)),
                              Some("error" -> ujson.Str(err))
                            )
                          case Right(range) =>
                            renderRange(az, root, uri, range, argsWithDefaultDetail) match
                              case None =>
                                jobj(
                                  Some("target" -> ujson.Str(entry.target)),
                                  Some(
                                    "requestedRange" -> ujson.Str(
                                      s"${lr.startLine}-${lr.endLine}"
                                    )
                                  ),
                                  Some("found" -> ujson.Bool(false)),
                                  Some("error" -> ujson.Str(s"'${uri.value}' has no such range"))
                                )
                              case Some(res) =>
                                ujson.Obj.from(
                                  res.obj.toSeq ++ Seq(
                                    "target" -> ujson.Str(entry.target),
                                    "requestedRange" -> ujson.Str(
                                      s"${lr.startLine}-${lr.endLine}"
                                    ),
                                    "found" -> ujson.Bool(true)
                                  )
                                )
                      }
                }
                jobj(
                  Some("query" -> ujson.Str(query)),
                  Some("results" -> ujson.Arr.from(results))
                )
          }
        )
```

Add the resolution helper to `McpToolsSupport` (near `resolveSymbolOrFqn`, e.g. directly after it):

```scala
  /** Resolve a `RangeSelector.Entry.target` to the `DocumentUri` `source_ranges` should read: a
    * literal file path (appending `.scala` when the given path has no known source extension and
    * doesn't exist as-is) for a file-shaped target, or the defining file of a resolved symbol/FQN
    * otherwise. `None` when neither resolves.
    */
  private[mcp] def resolveRangeTarget(
      az: Analyzer,
      root: java.nio.file.Path,
      target: String
  ): Option[DocumentUri] =
    if RangeSelector.looksLikeFileTarget(target) then
      val candidate =
        if java.nio.file.Files.isRegularFile(root.resolve(target)) then target
        else s"$target.scala"
      if java.nio.file.Files.isRegularFile(root.resolve(candidate)) then
        DocumentUri.from(candidate).toOption
      else None
    else resolveSymbolOrFqn(az, target).flatMap { case (sym, _) => az.symbolDefinitionRange(sym) }
      .map { case (uri, _) => uri }
```

(`resolveRangeTarget` takes `root` — update the call site above to
`resolveRangeTarget(az, root, entry.target)`.) Add
`import com.github.mercurievv.scalasemantic.analysis.RangeSelector` at the top of `McpTools.scala`
next to the existing `SourceSentinel` import.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mill mcp.test` (unfiltered)
Expected: `SourceRangesSuite` and `SourceRangesFqnSuite` both pass, 0 failed; full suite still green
(no regression in the other ~469 mcp tests).

- [ ] **Step 5: Commit**

```bash
git add mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/SourceRangesSuite.scala \
        mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/SourceRangesFqnSuite.scala
git commit -m "feat(mcp): add source_ranges tool for multi-file/multi-range detail=full deep dives"
```

---

## Explicitly out of scope

- Write mode for `source_ranges` (it is read-only, like `symbol_source`/`source_around_position`).
- A golden-locked fixture test (docs-golden style) — skipped in favor of structural assertions,
  since the primary value here is the selector grammar and multi-target/multi-range plumbing, not
  a specific rendered payload (already golden-locked by the existing `detail=full`/sentinel tests
  this tool delegates to via `renderRange`).
