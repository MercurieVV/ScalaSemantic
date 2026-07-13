# annotated_source enrichment — design

Status: **design locked, not yet implemented.** Governs the enrichment surface of the
`annotated_source` tool (and any future source-returning tool sharing `SourceView`).

## 1. Who consumes this and what they pay

`annotated_source` output goes straight into an LLM agent's context — it is the sanctioned
"read a `.scala` file" path (replaces `cat`/`Read`). Enrichment makes the compiler's invisible
insertions explicit so the agent sees what the compiler saw, not just the surface text.

There are **two independent cost centers**, and they are not the same size:

| Cost | Magnitude | Paid |
|------|-----------|------|
| **Inline tokens** — note text in context | ~5–35 tokens per enriched line | once, always |
| **Follow-up round-trip** — agent misreads a note → fires `type_at_position` / `resolve_implicits`, or makes a wrong edit → re-read | ~200–600 tokens + latency + wrong-edit risk | only when a note is *ambiguous and needed* |

Consequence: **one avoided follow-up pays for 10+ enriched lines.** Clarity that prevents a
round-trip is almost always worth its inline cost. The false economy is *ambiguity* — cheap inline,
but it maximizes downstream cost exactly when the fact matters.

## 2. Ambiguity is strictly dominated — retire `col N`

The current renderer pins positional notes with `col N` (1-based column). That forces the reader to
count characters — LLMs are unreliable at exact offset arithmetic — and a flat, `;`-joined note list
hides nesting (which `using` feeds which call). `col N` costs ~the same tokens as a token-anchored
note yet is harder to read: it loses on every axis. **It is removed** in favor of the axes below.

The real lever is not ambiguity but **density and opt-in**: let the caller choose how much
enrichment for the task phase (cheap *scan* vs. complete *reason/edit*), while every note that *is*
shown is unambiguous.

## 3. The enrichment axes (all orthogonal)

Four independent axes. `format` already exists; the other three are new. All notes stay unambiguous
at every setting.

| Axis | Param | Values (default **bold**) | Concern |
|------|-------|---------------------------|---------|
| Presentation | `format` | **annotated** / compilable / plain | how notes attach to lines |
| Enrichment depth | `detail` | **terse** / full | how much of each insertion is shown |
| Name resolution | `symbols` | **off** / on | origin package of the names used |
| Doc comments | `docs` | **keep** / strip | keep or drop Scaladoc/`//` to save tokens |

Plus the existing `annotationsOnly` (return only annotated lines) — a fifth thrift lever,
independent of all four.

### 3.1 `format` — presentation (unchanged)

- `annotated` (default): gutter + `⟹` notes. Densest; **not** valid Scala (read-only view).
- `compilable`: notes as trailing `// ⟹` comments, no gutter. Valid, pasteable Scala.
- `plain`: raw file, no notes.

### 3.2 `detail` — enrichment depth (new)

Governs how each of the four insertion kinds (`inferred-type`, `inferred-type-args`,
`implicit-args`, `implicit-conversion`) is rendered.

- **`terse`** (default) — **token-anchored, flat**. Each positional note is anchored to the
  *identifier at that offset* instead of a column number; one note per fact. Cheap scan, still
  correct. Nesting of implicit args is not spelled out.
- **`full`** — **elaborated expression**. Each call site's insertions are rendered as the
  fully-desugared call in real Scala: inferred type args, inserted `.apply`, and **nested**
  `using` arguments all inline. No `col`, no legend needed — reads as code. For the
  "about to edit / reason about implicits" phase.

Worst line of the `Enrich.scala` fixture (`val out = render(List(1, 2, 3))`):

```
retired  // ⟹ : String; (using listShow); col 11 [List[Int]]; col 18 apply(…); col 18 [Int]; (using intShow)
terse    // ⟹ : String; render[List[Int]]; List.apply[Int]; (using listShow); (using intShow)
full     // ⟹ render[List[Int]](List.apply[Int](1, 2, 3))(using listShow[Int](using intShow)): String
```

### 3.3 `symbols` — name resolution (new)

Answers "which package does this `Foo` mid-code belong to" without bloating the body. When `on`,
append a compact **symbol legend**: each distinct type/term *used* in the file → its dotted FQN,
deduped, **skipping the file's own package and `scala.*` / `java.lang.*`** (universally known — pure
noise). Keyed on the compiler's resolved symbol for each occurrence, so it covers names that need no
import too.

```
// symbols: List → scala.collection.immutable.List
```

Why a legend and not inline FQN or exploded imports:

- **Inline FQN on every type** bloats and *hurts* readability (`scala.Predef.String` everywhere) —
  the opposite of the goal.
- **Exploding `import foo.*` into explicit imports** is idiomatic under `format=compilable` and
  especially useful for **givens/extensions** (naming exactly which given is active). But it only
  resolves names that entered scope *through an import*; it **misses** same-package types, inherited
  / `export`ed members, and implicitly-summoned givens — precisely the mid-code names an agent
  wonders about. The occurrence-derived legend covers all of those and needs the same underlying
  analysis. So: legend is the core mechanism; **import-explosion is offered only as the
  `format=compilable` rendering of `symbols=on`**, restricted to imported entities, with its limits
  documented — never the default.

  *Implemented* (`Analyzer.explodeImports`): with `format=compilable` + `symbols=on`, each
  `import X.*` / `import X.given` line is rewritten to an explicit `import X.member` (or braced
  `import X.{a, b}` for several) naming just the members of `X` the file actually uses. Used members
  are collected from **both** occurrences and synthetics — an implicitly-summoned given
  (`render(3.14)` → `doubleShow`) has no textual occurrence, only a synthetic `IdTree`, so
  occurrences alone would miss it. The braced form stays on one physical line, preserving annotation
  offsets. A line is left untouched when its prefix or used members cannot be resolved. See the
  `annotated_source_enrich_symbols.scala` golden for the worked example.

| Name origin | exploded imports resolve? | occurrence legend resolves? |
|-------------|:-:|:-:|
| imported type/def | ✅ | ✅ |
| same-package (no import) | ❌ | ✅ |
| inherited / `export`ed | ❌ | ✅ |
| implicitly summoned given | ❌ | ✅ |
| extension from a given | ⚠️ only if given imported | ✅ |

### 3.4 `docs` — doc comments (new)

- **`keep`** (default): Scaladoc `/** … */` and `//` comments stay — they carry intent.
- **`strip`**: drop comments for a pure-code, token-lean view (scanning structure/behavior when
  prose is not needed). Text-level transform over the source lines; independent of the index.

## 4. API shape

```
annotated_source(uri,
  format         = annotated | compilable | plain   // default annotated
  detail         = terse | full                      // default terse
  symbols        = off | on                          // default off
  docs           = keep | strip                      // default keep
  annotationsOnly = false)                            // default false
```

Defaults reproduce today's behavior minus `col N` (which `terse` replaces token-anchored). Tool
description must guide the agent to escalate terse→full and flip `symbols=on` before an edit or
implicit-reasoning task.

## 5. Feasibility & corpus fit (Scala source + SemanticDB)

Grounded in `analysis/.../AnalyzerHelpers.scala` and `Analyzer.sourceAnnotations`.

- **`terse` (token-anchored)** — **low risk.** Every `SourceAnnotation` already carries `line` +
  `character`; the renderer already has the source lines. Look up the identifier at the offset,
  drop `col N`. Contained change in `SourceView` (`McpTools.scala:159-169`).
- **`symbols` legend** — **low risk; empirically confirmed.** `doc.occurrences` maps every
  reference to a symbol; `index.info(symbol)` gives kind + signature; `packageDotted` / `joinFqn`
  (`AnalyzerHelpers.scala:137-149`) already render dotted FQNs. The `Enrich.scala` dump shows every
  ref fully resolved (`scala/collection/immutable/List#`, `java/lang/String#`), confirming the
  `scala/` + `java/lang/` predef skip-list is exactly the noise to drop. Collect distinct type
  symbols, apply the skip-list. No tree-walking.
- **`docs` strip** — **low risk, but text-careful.** Comments are **not** in SemanticDB — they live
  only in raw source, so stripping is a pure text pass over `lines`. Must respect string literals
  and nested block comments; otherwise trivial.
- **`full` (elaborated)** — **highest risk; corpus fit favorable but with real caveats.** Verified
  empirically by dumping the `Enrich.scala` synthetics (via scalameta `semanticdb-shared`). Findings
  on `val out = render(List(1, 2, 3))`:
  - **Nesting is present.** The using-args synthetic is
    `ApplyTree(OriginalTree(render), [ApplyTree(IdTree(listShow), [IdTree(intShow)])])` — i.e.
    `listShow(using intShow)` nests inside one synthetic. The current renderer discards it
    (`insertedName` recurses to the head name only). The hard part is done by the corpus.
  - **Caveat 1 — merge co-ranged synthetics.** A call's **type-application** (`render[List[Int]]`)
    and its **using-application** (`render(using …)`) are *separate* synthetics both anchored at the
    same range (`14,10`). `full` must merge them into one call.
  - **Caveat 2 — dedup overlaps.** `List.apply[Int]` is emitted as both a bare `TypeApplyTree` and a
    full `ApplyTree` at `14,17`; the inner `intShow` appears both nested (above) and as its own tail
    synthetic at `14,31`. Must dedup by range.
  - **Caveat 3 — not every type-arg is materialized.** `listShow[Int]`'s `[Int]` has **no**
    synthetic, so a "fully elaborated" render cannot always show every type argument. `full` must
    reconstruct from the richest synthetic per range and tolerate missing type-args.

  Net: a real model change (flat `SourceAnnotation` → a per-call-site structured note that merges +
  dedups by range). Bounded, but the caveats make it the one axis worth a **spike before
  committing**.

## 6. Golden impact

`DocsEnrichingExamplesGoldenSuite` auto-writes a golden on first run and then locks it; the source
text lives in a sibling `.scala` file. Each new mode gets its own regenerated fixture (e.g.
`annotated_source_enrich_full.scala`, `..._symbols.json`). Delete the stale golden to regenerate.

## 7. Implementation sequencing

1. **`terse`** — retire `col N`, token-anchor positional notes; regenerate golden. (Immediate win.)
2. **`symbols=on`** — occurrence legend + skip-list; new golden. Independent of 1.
3. **`docs=strip`** — comment stripper; new golden. Independent.
4. **`full`** — spike the co-ranged-synthetic stitch, then structured note + renderer; new golden.

Each step is independently shippable and independently golden-locked.
