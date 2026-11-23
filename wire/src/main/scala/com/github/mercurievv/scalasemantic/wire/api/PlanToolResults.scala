package com.github.mercurievv.scalasemantic.wire.api

import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

// Wire shapes of the read-only edit-plan tools. Schema-only, like `SourceToolResults`: the tools
// keep building their JSON, and `PlanToolSchemaSuite` validates real answers against these types.

/** `rename_plan`. Each edit is `uri:line:startCol-endCol` (0-based). */
final case class RenamePlanResult(
    @description("the symbol being renamed") symbol: String,
    @description("`old -> new`") rename: String,
    @description("number of edits") editCount: Int,
    @description("`uri:line:startCol-endCol` per occurrence (0-based)") edits: List[String]
) derives Schema

final case class RenameSummary(
    @description("the symbol being renamed") symbol: String,
    @description("`old -> new`") rename: String,
    @description("number of edits for this symbol") editCount: Int
) derives Schema

final case class RenameConflict(
    @description("`uri:line:startCol-endCol` of the first overlapping edit") rangeA: String,
    @description("`uri:line:startCol-endCol` of the second overlapping edit") rangeB: String,
    @description("why they conflict") reason: String
) derives Schema

/** `batch_rename_plan`: one `rename_plan` per entry, plus literal edit-range conflicts. */
final case class BatchRenamePlanResult(
    @description("per-symbol summaries, in request order") perSymbol: List[RenameSummary],
    @description("number of edits across all renames") combinedEditCount: Int,
    @description("every edit, `uri:line:startCol-endCol`") combinedEdits: List[String],
    @description("number of overlapping edit ranges") conflictCount: Int,
    @description("overlapping edit ranges (absent when none)")
    conflicts: Option[List[RenameConflict]]
) derives Schema

final case class ImportEdit(
    @description("file to edit") uri: String,
    @description("import to remove") remove: Option[String],
    @description("import to add") add: Option[String]
) derives Schema

/** `move_plan`. */
final case class MovePlanResult(
    @description("the symbol being moved") symbol: String,
    @description("`old FQN -> new FQN`") move: String,
    @description("`uri:line:col` of the definition to cut") definition: Option[String],
    @description("number of references") referenceCount: Int,
    @description("`uri:line:col` of every reference (absent when none)")
    references: Option[List[String]],
    @description("per-file imports to add/remove (absent when none)")
    imports: Option[List[ImportEdit]]
) derives Schema

/** `extract_method_plan`. */
final case class ExtractMethodPlanResult(
    @description("document uri") uri: String,
    @description("name of the enclosing method") enclosingMethod: Option[String],
    @description("the method to insert in the enclosing scope") signature: String,
    @description("the call that replaces the selection") call: String,
    @description("`name: Type` of each parameter (absent when none)")
    parameters: Option[List[String]],
    @description("`name: Type` of each value later code still uses (absent when none)")
    returns: Option[List[String]],
    @description("the extracted method's return type") returnType: String
) derives Schema
