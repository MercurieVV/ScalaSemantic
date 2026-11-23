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

/** Every reference of one file's requested ranges. `omitted` counts the RESOLVED rows dropped by
  * the output bound: an unresolved row is never dropped, because it is the only kind of row a
  * caller acts on. Within each group the kept rows are in source order. `error` is set — with
  * `references` empty — when the file could not be read as Scala at all; zero references and a
  * syntax error are different answers and must not be confused.
  *
  * There is deliberately no `pcFallback` here: whether the answer came from a weaker backend is a
  * fact about how the SERVER obtained the text (on-disk vs `source` argument), not about the
  * analysis, so the MCP layer appends that field to the written object
  * (`McpTools.pcFallbackFields`, the same convention `hole_context` and `source_ranges` use).
  */
final case class FileReferences(
    uri: String,
    references: List[Reference],
    omitted: Int,
    error: Option[String]
) derives ReadWriter

/** The wire result: one entry per file asked about. */
final case class ReferenceResult(files: List[FileReferences]) derives ReadWriter
