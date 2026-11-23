package com.github.mercurievv.scalasemantic.wire

import com.github.mercurievv.scalasemantic.model.BatchRenamePlan
import com.github.mercurievv.scalasemantic.model.CallEdge
import com.github.mercurievv.scalasemantic.model.CallGraphPath
import com.github.mercurievv.scalasemantic.model.CallHierarchy
import com.github.mercurievv.scalasemantic.model.CallHierarchyNode
import com.github.mercurievv.scalasemantic.model.ClassHierarchy
import com.github.mercurievv.scalasemantic.model.DependencyCycle
import com.github.mercurievv.scalasemantic.model.DimensionMetrics
import com.github.mercurievv.scalasemantic.model.DuplicateOccurrence
import com.github.mercurievv.scalasemantic.model.DuplicationGroup
import com.github.mercurievv.scalasemantic.model.DuplicationsResult
import com.github.mercurievv.scalasemantic.model.ExtractBinding
import com.github.mercurievv.scalasemantic.model.ExtractMethodPlan
import com.github.mercurievv.scalasemantic.model.ImplicitCandidate
import com.github.mercurievv.scalasemantic.model.ImplicitChain
import com.github.mercurievv.scalasemantic.model.ImplicitChainStep
import com.github.mercurievv.scalasemantic.model.ImplicitResolution
import com.github.mercurievv.scalasemantic.model.ImplicitTree
import com.github.mercurievv.scalasemantic.model.Location
import com.github.mercurievv.scalasemantic.model.MemberInfo
import com.github.mercurievv.scalasemantic.model.MembersResult
import com.github.mercurievv.scalasemantic.model.MethodSignature
import com.github.mercurievv.scalasemantic.model.ModuleEdge
import com.github.mercurievv.scalasemantic.model.ModuleStructure
import com.github.mercurievv.scalasemantic.model.MoveImport
import com.github.mercurievv.scalasemantic.model.MovePlan
import com.github.mercurievv.scalasemantic.model.OutlineEntry
import com.github.mercurievv.scalasemantic.model.OverloadsResult
import com.github.mercurievv.scalasemantic.model.Parameter
import com.github.mercurievv.scalasemantic.model.ParameterList
import com.github.mercurievv.scalasemantic.model.Position
import com.github.mercurievv.scalasemantic.model.Range
import com.github.mercurievv.scalasemantic.model.RelatedUsageGroup
import com.github.mercurievv.scalasemantic.model.RenameConflict
import com.github.mercurievv.scalasemantic.model.RenameEdit
import com.github.mercurievv.scalasemantic.model.RenamePlan
import com.github.mercurievv.scalasemantic.model.RenameRequest
import com.github.mercurievv.scalasemantic.model.SourceAnnotation
import com.github.mercurievv.scalasemantic.model.StructureResult
import com.github.mercurievv.scalasemantic.model.SymbolDependency
import com.github.mercurievv.scalasemantic.model.SymbolKind
import com.github.mercurievv.scalasemantic.model.SymbolRef
import com.github.mercurievv.scalasemantic.model.SymbolStructure
import com.github.mercurievv.scalasemantic.model.TypeAtPosition
import com.github.mercurievv.scalasemantic.model.UsageHit
import com.github.mercurievv.scalasemantic.model.UsagesResult
import com.github.mercurievv.scalasemantic.model.ValueFlowEdge
import com.github.mercurievv.scalasemantic.model.ValueFlowNode
import com.github.mercurievv.scalasemantic.model.ValueFlowResult
import com.github.mercurievv.scalasemantic.model.ValueFlowTerminal
import io.circe.Decoder
import io.circe.Encoder

/** Circe codecs for every analysis model in `Models.scala` (generated from its case-class list by
  * scripts/gen-model-codecs.sc; keep in sync when a model is added). The result-model codecs that
  * tools return as they are, with their schemas, live in [[AnalysisCodecs]].
  */
object ModelCodecs:

  given Encoder[SymbolKind] = Encoder[String].contramap(_.value)
  given Decoder[SymbolKind] = Decoder[String].map(SymbolKind.from)

  given Encoder.AsObject[Position] = Encoder.AsObject.derived
  given Decoder[Position] = Decoder.derived
  given Encoder.AsObject[Range] = Encoder.AsObject.derived
  given Decoder[Range] = Decoder.derived
  given Encoder.AsObject[Location] = Encoder.AsObject.derived
  given Decoder[Location] = Decoder.derived
  given Encoder.AsObject[SymbolRef] = Encoder.AsObject.derived
  given Decoder[SymbolRef] = Decoder.derived
  given Encoder.AsObject[UsagesResult] = Encoder.AsObject.derived
  given Decoder[UsagesResult] = Decoder.derived
  given Encoder.AsObject[UsageHit] = Encoder.AsObject.derived
  given Decoder[UsageHit] = Decoder.derived
  given Encoder.AsObject[RelatedUsageGroup] = Encoder.AsObject.derived
  given Decoder[RelatedUsageGroup] = Decoder.derived
  given Encoder.AsObject[Parameter] = Encoder.AsObject.derived
  given Decoder[Parameter] = Decoder.derived
  given Encoder.AsObject[ParameterList] = Encoder.AsObject.derived
  given Decoder[ParameterList] = Decoder.derived
  given Encoder.AsObject[MethodSignature] = Encoder.AsObject.derived
  given Decoder[MethodSignature] = Decoder.derived
  given Encoder.AsObject[OverloadsResult] = Encoder.AsObject.derived
  given Decoder[OverloadsResult] = Decoder.derived
  given Encoder.AsObject[ClassHierarchy] = Encoder.AsObject.derived
  given Decoder[ClassHierarchy] = Decoder.derived
  given Encoder.AsObject[MemberInfo] = Encoder.AsObject.derived
  given Decoder[MemberInfo] = Decoder.derived
  given Encoder.AsObject[MembersResult] = Encoder.AsObject.derived
  given Decoder[MembersResult] = Decoder.derived
  given Encoder.AsObject[ImplicitCandidate] = Encoder.AsObject.derived
  given Decoder[ImplicitCandidate] = Decoder.derived
  given Encoder.AsObject[ImplicitResolution] = Encoder.AsObject.derived
  given Decoder[ImplicitResolution] = Decoder.derived
  given Encoder.AsObject[ImplicitChainStep] = Encoder.AsObject.derived
  given Decoder[ImplicitChainStep] = Decoder.derived
  given Encoder.AsObject[ImplicitTree] = Encoder.AsObject.derived
  given Decoder[ImplicitTree] = Decoder.derived
  given Encoder.AsObject[ImplicitChain] = Encoder.AsObject.derived
  given Decoder[ImplicitChain] = Decoder.derived
  given Encoder.AsObject[TypeAtPosition] = Encoder.AsObject.derived
  given Decoder[TypeAtPosition] = Decoder.derived
  given Encoder.AsObject[DimensionMetrics] = Encoder.AsObject.derived
  given Decoder[DimensionMetrics] = Decoder.derived
  given Encoder.AsObject[SymbolStructure] = Encoder.AsObject.derived
  given Decoder[SymbolStructure] = Decoder.derived
  given Encoder.AsObject[DependencyCycle] = Encoder.AsObject.derived
  given Decoder[DependencyCycle] = Decoder.derived
  given Encoder.AsObject[ModuleStructure] = Encoder.AsObject.derived
  given Decoder[ModuleStructure] = Decoder.derived
  given Encoder.AsObject[ModuleEdge] = Encoder.AsObject.derived
  given Decoder[ModuleEdge] = Decoder.derived
  given Encoder.AsObject[SymbolDependency] = Encoder.AsObject.derived
  given Decoder[SymbolDependency] = Decoder.derived
  given Encoder.AsObject[StructureResult] = Encoder.AsObject.derived
  given Decoder[StructureResult] = Decoder.derived
  given Encoder.AsObject[OutlineEntry] = Encoder.AsObject.derived
  given Decoder[OutlineEntry] = Decoder.derived
  given Encoder.AsObject[SourceAnnotation] = Encoder.AsObject.derived
  given Decoder[SourceAnnotation] = Decoder.derived
  given Encoder.AsObject[RenameEdit] = Encoder.AsObject.derived
  given Decoder[RenameEdit] = Decoder.derived
  given Encoder.AsObject[RenamePlan] = Encoder.AsObject.derived
  given Decoder[RenamePlan] = Decoder.derived
  given Encoder.AsObject[RenameRequest] = Encoder.AsObject.derived
  given Decoder[RenameRequest] = Decoder.derived
  given Encoder.AsObject[RenameConflict] = Encoder.AsObject.derived
  given Decoder[RenameConflict] = Decoder.derived
  given Encoder.AsObject[BatchRenamePlan] = Encoder.AsObject.derived
  given Decoder[BatchRenamePlan] = Decoder.derived
  given Encoder.AsObject[MoveImport] = Encoder.AsObject.derived
  given Decoder[MoveImport] = Decoder.derived
  given Encoder.AsObject[MovePlan] = Encoder.AsObject.derived
  given Decoder[MovePlan] = Decoder.derived
  given Encoder.AsObject[ExtractBinding] = Encoder.AsObject.derived
  given Decoder[ExtractBinding] = Decoder.derived
  given Encoder.AsObject[ExtractMethodPlan] = Encoder.AsObject.derived
  given Decoder[ExtractMethodPlan] = Decoder.derived
  given Encoder.AsObject[CallEdge] = Encoder.AsObject.derived
  given Decoder[CallEdge] = Decoder.derived
  given Encoder.AsObject[CallGraphPath] = Encoder.AsObject.derived
  given Decoder[CallGraphPath] = Decoder.derived
  given Encoder.AsObject[CallHierarchyNode] = Encoder.AsObject.derived
  given Decoder[CallHierarchyNode] = Decoder.derived
  given Encoder.AsObject[CallHierarchy] = Encoder.AsObject.derived
  given Decoder[CallHierarchy] = Decoder.derived
  given Encoder.AsObject[ValueFlowNode] = Encoder.AsObject.derived
  given Decoder[ValueFlowNode] = Decoder.derived
  given Encoder.AsObject[ValueFlowEdge] = Encoder.AsObject.derived
  given Decoder[ValueFlowEdge] = Decoder.derived
  given Encoder.AsObject[ValueFlowTerminal] = Encoder.AsObject.derived
  given Decoder[ValueFlowTerminal] = Decoder.derived
  given Encoder.AsObject[ValueFlowResult] = Encoder.AsObject.derived
  given Decoder[ValueFlowResult] = Decoder.derived
  given Encoder.AsObject[DuplicateOccurrence] = Encoder.AsObject.derived
  given Decoder[DuplicateOccurrence] = Decoder.derived
  given Encoder.AsObject[DuplicationGroup] = Encoder.AsObject.derived
  given Decoder[DuplicationGroup] = Decoder.derived
  given Encoder.AsObject[DuplicationsResult] = Encoder.AsObject.derived
  given Decoder[DuplicationsResult] = Decoder.derived
