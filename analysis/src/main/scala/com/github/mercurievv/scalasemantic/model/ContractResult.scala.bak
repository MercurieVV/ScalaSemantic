package com.github.mercurievv.scalasemantic.model

import upickle.default.*

/** One contract clause (spec 2026-09-27-stainless-vericoding-design §1): its source `text`, and a
  * `shape` — a hash of the clause's syntax tree — so reformatting never reads as a change while any
  * edit to the clause itself does.
  */
final case class Clause(text: String, shape: String) derives ReadWriter

/** The Stainless contract of one `def`. `key` is the enclosing owner path plus the def name
  * (`PureKernels.toPcm16`); when overloads or other defs share a key within one file, each gets its
  * parameter list appended: `PureKernels.f(a: Int, b: Int)`. If collisions persist (e.g., identical
  * defs on one line), each gets a sequence number: `PureKernels.f(a: Int, b: Int)#1`. `line` is the
  * def's 0-based start line.
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
