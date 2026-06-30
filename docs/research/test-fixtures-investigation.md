# Test Fixtures Investigation — External Scala/SemanticDB Fixture Sets

**Context:** Issue #80 asked whether the Scala/ScalaMeta ecosystem ships curated Scala code
designed for testing language features, and if so, whether we should use those sets instead of
writing custom fixtures.

---

## TL;DR

Yes — curated fixture sets exist, most notably in `scala/scala3` and `scalameta/scalameta`. The
most directly relevant is the Scala 3 compiler's own `tests/semanticdb/` directory, which contains
`.scala` files paired with `.expect` files that describe precise expected SemanticDB output. These
are well-maintained and cover language features that are hard to craft by hand. However, they do not
replace our current `compat-fixtures` approach — they serve a different purpose. The right strategy
is to treat them as **a reference and an inspiration source** for gaps in our own fixture coverage,
not as a drop-in replacement.

---

## 1. What Exists in the Ecosystem

### 1.1 `scala/scala3` — `tests/semanticdb/`

**URL:** https://github.com/scala/scala3/tree/main/tests/semanticdb

This is the most directly relevant fixture set. The Scala 3 compiler (Dotty) ships a dedicated
`tests/semanticdb/` directory that exercises the compiler's SemanticDB emitter. Each file is a
carefully crafted `.scala` source paired with an `.expect` file that records the expected protobuf
text for every symbol and occurrence in that file.

Coverage includes:

| Feature area | Example files |
|---|---|
| Basic classes, traits, objects | `example.scala` |
| Type parameters and bounds | `typeParams.scala` |
| Implicit / given / using | `implicits.scala`, `givens.scala` |
| Extension methods | `extensions.scala` |
| Enum types | `enums.scala` |
| Union / intersection / literal types | `typeAliases.scala` |
| Opaque type aliases | `opaqueTypes.scala` |
| Match types | `matchTypes.scala` |
| Inline and macro | `inline.scala` |
| Constructors and secondary ctors | `constructors.scala` |
| Package objects / top-level defs | `packageObjects.scala` |
| Overloads and default params | `overloads.scala` |

**Scala version:** Scala 3 only. No Scala 2 counterparts exist in this set (by design — it tests
the 3.x emitter).

**License:** Apache 2.0.

**How to use:** The `.scala` files compile cleanly with a recent Scala 3. You can check them out
and compile them with `semanticdbEnabled` to produce `.semanticdb` files, then load them with
`SemanticIndex.fromRoots(...)`. The `.expect` files are useful for *verifying* that the emitter
behaves as expected but are not required for our kind of analysis testing.

### 1.2 `scalameta/scalameta` — test resources

**URL:** https://github.com/scalameta/scalameta/tree/main/tests

ScalaMeta's own test suite has `.scala` files used to test their parser and tree APIs. These
exercise the full surface of Scala syntax and come in both Scala 2 and Scala 3 dialects. However,
they are not focused on SemanticDB structure — they test *syntactic* tree shapes rather than
resolved symbols. Relevant for verifying source ranges and token structure, less relevant for
hierarchy/signature/implicit tests.

A smaller but more targeted sub-directory is `scalameta/semanticdb/` (used in scalameta's own
SemanticDB printer tests). These pair `.scala` sources with expected output in a format similar to
the Scala 3 `.expect` files.

**Scala version:** Mixed; both 2 and 3 variants exist. Separate `scala-2` and `scala-3`
sub-directories where the syntax differs.

**License:** BSD 3-Clause.

### 1.3 `scalameta/metals` — test workspace fixtures

**URL:** https://github.com/scalameta/metals/tree/main/tests/unit/src/test/resources

Metals ships hundreds of `.scala` workspace fixtures used for LSP feature testing (go-to-def,
hover, completion, rename, highlighting). Each fixture is a small, focused file that exercises one
LSP scenario. Some examples: `definition/`, `hover/`, `implementation/`, `rename/`,
`semanticTokens/`.

These files are well-maintained, diverse, and cover many obscure language patterns (path-dependent
types, self-types, structural types, higher-kinded, context bounds, etc.). The downside: they are
written for LSP testing, not SemanticDB analysis, and many rely on the Metals compiler driver
rather than the plain compiler + SemanticDB emitter.

**Scala version:** Mix of 2.13 and 3.x, version-specific subdirectories where needed.

**License:** Apache 2.0.

### 1.4 `scala/scala` — `test/files/`

**URL:** https://github.com/scala/scala/tree/2.13.x/test/files

The Scala 2 compiler ships thousands of test files under `test/files/pos/` (should compile),
`test/files/neg/` (should fail), and `test/files/run/` (should run). These cover every Scala 2
language feature in exhaustive detail.

For SemanticDB-specific purposes, the subset that matters is the positive tests (`pos/`) — these
compile cleanly and would produce valid `.semanticdb` output. Files such as
`test/files/pos/implicits.scala`, `test/files/pos/traits.scala`, `test/files/pos/t*.scala` (bug
regression tests) provide broad coverage of Scala 2 patterns.

**Scala version:** Scala 2.13 only.

**License:** Apache 2.0.

### 1.5 `scalacenter/scalafix` — scalafix-testkit pattern

**URL:** https://github.com/scalacenter/scalafix/tree/main/scalafix-testkit

Scalafix does not ship standalone `.scala` fixtures, but it defines the **testkit pattern**: an
input `.scala` file with inline comments marking expected diagnostics or rewrites, paired with an
expected output `.scala` file. This is a pattern, not a fixture set. It is useful as a model for
how to structure paired input/output tests for rewriting or analysis rules, but does not provide
fixture code we can directly reuse.

---

## 2. What Our Project Already Has

The `compat-fixtures/` module already implements the right approach:

- Separate `scala-2.13/` and `scala-3/` source trees with identical package structure
- Minimally targeted files: `BasicClasses`, `Inheritance`, `Generics`, `Implicits`, `CallGraph`,
  `Overloads`, and `VersionSpecific` (Scala 3 only)
- `CompatSuite` loads pre-generated golden `.semanticdb` from
  `analysis/src/test/resources/compat/scala-<binVersion>/` and runs structural assertions
- `VersionSpecific.scala` covers Scala-3-only shapes (union types, intersection types, extension
  methods, literal types) with no Scala 2 counterpart

This is more tightly integrated, more predictable (no external source dependency), and exactly
scoped to the analyzer's assertion needs.

---

## 3. Gap Analysis: What Our Fixtures Are Missing

Comparing our current coverage against the feature matrix in `scala/scala3/tests/semanticdb/`:

| Feature | Covered in compat-fixtures? | Notes |
|---|---|---|
| Basic class hierarchy | Yes (`BasicClasses`, `Inheritance`) | |
| Generic typeclass | Yes (`Generics`, `Show[A]`) | |
| Implicit/given instances | Yes (`Implicits`) | |
| Chained givens | Yes (listShow) | |
| Overloads | Yes (`Overloads`) | |
| Call graph | Yes (`CallGraph`) | |
| Union / intersection types | Yes (Scala 3 `VersionSpecific`) | Scala 3 only |
| Extension methods | Yes (Scala 3 `VersionSpecific`) | Scala 3 only |
| Literal types | Yes (Scala 3 `VersionSpecific`) | Scala 3 only |
| Enum types | **No** | Scala 3 only; no fixture yet |
| Opaque type aliases | **No** | Scala 3 only |
| Match types | **No** | Scala 3 only |
| Type lambdas / higher-kinded | **No** | Both versions |
| Self-type annotations | **No** | Both versions |
| Package objects (Scala 2) | **No** | Scala 2.13 only |
| Macro / inline | **No** | Scala 3 only; low priority |
| Context bounds (Scala 2) | **No** | |
| Secondary constructors | **No** | Both versions |
| Default parameters | **No** | Both versions |
| Structural types | **No** | Both versions |
| Type aliases | **No** | Both versions |

The gaps are real but the priority items are modest additions to the existing compat-fixtures files,
not a wholesale replacement with external sources.

---

## 4. Recommendation

**Keep custom fixtures. Grow them incrementally using external repos as reference.**

Rationale:

1. **Control over SemanticDB content.** Our tests assert specific symbol strings and structural
   facts. External fixtures may be reformatted, renamed, or restructured in upstream PRs — that
   would silently break our golden files without any code change on our side.

2. **No build-time fetching.** External fixture sets cannot be referenced at test time without
   either vendoring them (bloating the repo) or adding a download step (fragile CI). The
   `compat-fixtures` module compiles locally and produces deterministic output.

3. **Scala version alignment.** The `compat-fixtures` module already handles the 2.13/3.x split
   with separate source trees under `src/main/scala-2.13/` and `src/main/scala-3/`. External
   repos use various mechanisms that would need adapting.

4. **Minimal surface.** Each fixture file in `compat-fixtures` exists because `CompatSuite` uses
   it. External suites contain hundreds of files we would never query, producing `.semanticdb`
   overhead during the `compatGoldenAll` generation step.

**What external repos are useful for:**

- **`scala/scala3/tests/semanticdb/`** is the best *reference* when adding a new fixture file.
  Before writing a `EnumTypes.scala` fixture from scratch, check what shapes the Dotty team
  already validated in their `.expect` files — that tells you exactly which symbol strings and
  occurrence roles to expect.

- **`scalameta/metals` test resources** are a good *inspiration* for edge cases (path-dependent
  types, context bounds, self-types) when CompatSuite assertions start failing on a new compiler
  version and you need to understand why.

---

## 5. How to Reference External Fixtures in CompatSuite (if needed)

If a specific feature gap warrants using an upstream file rather than authoring one ourselves, the
cleanest approach is **vendoring a single file** into the appropriate `scala-2.13/` or `scala-3/`
subtree of `compat-fixtures/`, with a header comment crediting the source and its license. Example:

```scala
// Vendored from scala/scala3 tests/semanticdb/enums.scala (Apache 2.0).
// Original: https://github.com/scala/scala3/blob/main/tests/semanticdb/enums.scala
// Adapted: reduced to the subset exercised by EnumTypesSuite assertions.
package com.github.mercurievv.scalasemantic.compat

enum Color:
  case Red, Green, Blue
```

This keeps the test self-contained, avoids runtime downloads, and preserves attribution.
`CompatSuite` then picks it up automatically because it scans all symbols in the package
`com/github/mercurievv/scalasemantic/compat/`.

No `CompatSuite` code changes are required — the suite already iterates all symbols in the package
and runs structural assertions. New fixtures only need a matching assertion block for any new
symbol names they introduce.

---

## 6. Sources Checked

| Repo | URL | Verdict |
|---|---|---|
| scala/scala3 | https://github.com/scala/scala3/tree/main/tests/semanticdb | Best reference for SemanticDB shapes |
| scalameta/scalameta | https://github.com/scalameta/scalameta/tree/main/tests | Syntactic; less useful for us |
| scalameta/metals | https://github.com/scalameta/metals/tree/main/tests/unit/src/test/resources | Good edge-case inspiration |
| scala/scala (2.x) | https://github.com/scala/scala/tree/2.13.x/test/files/pos | Reference for Scala 2 feature shapes |
| scalacenter/scalafix | https://github.com/scalacenter/scalafix/tree/main/scalafix-testkit | Pattern only, no fixtures |
