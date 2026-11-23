package com.github.mercurievv.scalasemantic.wire

import com.github.mercurievv.scalasemantic.model.Clause
import com.github.mercurievv.scalasemantic.model.Contract
import com.github.mercurievv.scalasemantic.model.ContractResult
import com.github.mercurievv.scalasemantic.model.Enclosing
import com.github.mercurievv.scalasemantic.model.FileContracts
import com.github.mercurievv.scalasemantic.model.FileLiterals
import com.github.mercurievv.scalasemantic.model.FileReferences
import com.github.mercurievv.scalasemantic.model.HoleBinding
import com.github.mercurievv.scalasemantic.model.HoleContextResult
import com.github.mercurievv.scalasemantic.model.HoleExpected
import com.github.mercurievv.scalasemantic.model.HoleGiven
import com.github.mercurievv.scalasemantic.model.HoleMarker
import com.github.mercurievv.scalasemantic.model.HoleOmitted
import com.github.mercurievv.scalasemantic.model.HoleProducer
import com.github.mercurievv.scalasemantic.model.HoleType
import com.github.mercurievv.scalasemantic.model.Literal
import com.github.mercurievv.scalasemantic.model.LiteralResult
import com.github.mercurievv.scalasemantic.model.Reference
import com.github.mercurievv.scalasemantic.model.ReferenceResult
import io.circe.Encoder
import sttp.tapir.Schema

/** The JSON wire shape of the analysis models that tools return as they are (ADR 0006): circe
  * `Encoder`s and tapir `Schema`s, each derived once here so the encoder that writes a result and
  * the `outputSchema` that describes it cannot disagree. `analysis` itself stays JSON-free.
  *
  * Derivation is bottom-up: neither circe nor tapir derives a nested type on its own, so each type
  * is defined after the types it contains. An absent `Option` is an absent key on the wire (the
  * server's shared encode step drops `null`s), not an explicit `null`.
  */
object AnalysisCodecs:

  // ---- literal_tokens -----------------------------------------------------------------------
  given Encoder.AsObject[Enclosing] = Encoder.AsObject.derived
  given Schema[Enclosing] = Schema.derived
  given Encoder.AsObject[Literal] = Encoder.AsObject.derived
  given Schema[Literal] = Schema.derived
  given Encoder.AsObject[FileLiterals] = Encoder.AsObject.derived
  given Schema[FileLiterals] = Schema.derived
  given Encoder.AsObject[LiteralResult] = Encoder.AsObject.derived
  given Schema[LiteralResult] = Schema.derived

  // ---- contract_clauses ---------------------------------------------------------------------
  given Encoder.AsObject[Clause] = Encoder.AsObject.derived
  given Schema[Clause] = Schema.derived
  given Encoder.AsObject[Contract] = Encoder.AsObject.derived
  given Schema[Contract] = Schema.derived
  given Encoder.AsObject[FileContracts] = Encoder.AsObject.derived
  given Schema[FileContracts] = Schema.derived
  given Encoder.AsObject[ContractResult] = Encoder.AsObject.derived
  given Schema[ContractResult] = Schema.derived

  // ---- resolve_references -------------------------------------------------------------------
  given Encoder.AsObject[Reference] = Encoder.AsObject.derived
  given Schema[Reference] = Schema.derived
  given Encoder.AsObject[FileReferences] = Encoder.AsObject.derived
  given Schema[FileReferences] = Schema.derived
  given Encoder.AsObject[ReferenceResult] = Encoder.AsObject.derived
  given Schema[ReferenceResult] = Schema.derived

  // ---- hole_context -------------------------------------------------------------------------
  given Encoder.AsObject[HoleExpected] = Encoder.AsObject.derived
  given Schema[HoleExpected] = Schema.derived
  given Encoder.AsObject[HoleBinding] = Encoder.AsObject.derived
  given Schema[HoleBinding] = Schema.derived
  given Encoder.AsObject[HoleProducer] = Encoder.AsObject.derived
  given Schema[HoleProducer] = Schema.derived
  given Encoder.AsObject[HoleGiven] = Encoder.AsObject.derived
  given Schema[HoleGiven] = Schema.derived
  given Encoder.AsObject[HoleType] = Encoder.AsObject.derived
  given Schema[HoleType] = Schema.derived
  given Encoder.AsObject[HoleOmitted] = Encoder.AsObject.derived
  given Schema[HoleOmitted] = Schema.derived
  given Encoder.AsObject[HoleMarker] = Encoder.AsObject.derived
  given Schema[HoleMarker] = Schema.derived
  given Encoder.AsObject[HoleContextResult] = Encoder.AsObject.derived
  given Schema[HoleContextResult] = Schema.derived
