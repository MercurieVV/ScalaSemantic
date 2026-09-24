package com.github.mercurievv.scalasemantic.model

import upickle.default.*

/** One name the source mentions, joined against the compiler's view of it (spec
  * 2026-09-24-diff-scoped-verification-design §1). `resolved = false` is the interesting row: the
  * name is written in the source but the compiler emitted no occurrence over it, i.e. it does not
  * exist — a hallucinated member, a misspelled val, a type that was never imported.
  *
  *   - `kind`/`role` come from the syntax (scalameta): `type`/`term`/`method`/`package`, and
  *     `definition` (the name is being introduced here) vs `use`.
  *   - `symbol` is the SemanticDB symbol the compiler resolved to, absent when unresolved.
  *   - `origin` classifies a resolved symbol as `project`, `stdlib` or `classpath`.
  *   - `suggestions` are deterministic near-misses for an unresolved name; always empty for a
  *     resolved one.
  */
final case class Reference(
    line: Int,
    character: Int,
    name: String,
    kind: String,
    role: String,
    resolved: Boolean,
    symbol: Option[String],
    origin: Option[String],
    suggestions: List[String]
) derives ReadWriter

/** Every reference of one file's requested ranges. `omitted` counts rows dropped by the output
  * bound (the kept rows are the first in source order). `pcFallback` records that the answer came
  * from a weaker backend than the presentation compiler. `error` is set — with `references` empty —
  * when the file could not be read as Scala at all; zero references and a syntax error are
  * different answers and must not be confused.
  */
final case class FileReferences(
    uri: String,
    references: List[Reference],
    omitted: Int,
    pcFallback: Boolean,
    error: Option[String]
) derives ReadWriter

/** The wire result: one entry per file asked about. */
final case class ReferenceResult(files: List[FileReferences]) derives ReadWriter
