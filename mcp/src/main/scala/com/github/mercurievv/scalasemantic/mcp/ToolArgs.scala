package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.model.InputTypes.SourceDetail
import com.github.mercurievv.scalasemantic.model.InputTypes.SourceFormat
import io.circe.Decoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

// Typed tool arguments (ADR 0006, stage B). One `derives` gives the decoder that reads the request
// and the schema that becomes the tool's `inputSchema`. A field is required exactly when it is not
// an `Option`; defaults live in the tool body, and the description says what they are.

final case class FindSymbolArgs(
    @description("simple or partial name to search for") query: String,
    @description("max results (default 50)") limit: Option[Int],
    @description("match the display name exactly, case-insensitive (default false)")
    exact: Option[Boolean],
    @description("keep only this SymbolInformation kind, e.g. TRAIT, CLASS, METHOD")
    kind: Option[String],
    @description("glob on the symbol's definition uri; `*` matches any chars")
    pathFilter: Option[String],
    @description(
      "badge each result type with its structural layer/centrality/inCycle (default false)"
    )
    metrics: Option[Boolean]
) derives Decoder,
      Schema

final case class FindUsagesArgs(
    @description("SemanticDB symbol to search for") symbol: String,
    @description("max references to return (default 100)") limit: Option[Int],
    @description("reference offset for paging (default 0)") offset: Option[Int],
    @description("glob on document uri; `*` matches any chars (substring match)")
    pathFilter: Option[String],
    @description("sections to return: any of \"definitions\", \"references\" (default both)")
    include: Option[List[String]],
    @description("lines of surrounding source per hit (default 0 = no context, no extra file I/O)")
    contextLines: Option[Int],
    @description(
      "case-class relations to include: any of \"companion\", \"constructors\", \"copy\", " +
        "\"accessors\", \"apply\", \"unapply\" (default all; `[]` drops the section)"
    )
    related: Option[List[String]]
) derives Decoder,
      Schema

final case class MethodSignatureArgs(
    @description(
      "method/val/var symbol, OR a file path — a file with exactly one method returns its " +
        "signature; several methods return `{ candidates }` to re-call with one"
    )
    symbol: String,
    @description("include structured parameter breakdown (default false)")
    detailed: Option[Boolean],
    @description(
      "defining file's uri (path relative to project root); with `source`, overlays it"
    )
    uri: Option[String],
    @description("current full text of the file at `uri`; enables the live PC overlay")
    source: Option[String]
) derives Decoder,
      Schema

final case class ClassHierarchyArgs(
    @description("class or trait symbol, or a file path") symbol: String,
    @description("expand related types to {symbol,name,kind} (default false)")
    detailed: Option[Boolean],
    @description("glob on a related type's definition uri; `*` matches any chars")
    pathFilter: Option[String],
    @description(
      "sections to return: any of \"parents\", \"linearization\", \"knownSubtypes\" (default all)"
    )
    include: Option[List[String]],
    @description(
      "badge the queried type with its structural layer/centrality/inCycle (default false)"
    )
    metrics: Option[Boolean]
) derives Decoder,
      Schema

final case class FindOverloadsArgs(
    @description("any one overload's symbol, or a file path") symbol: String
) derives Decoder,
      Schema

final case class MembersArgs(
    @description("class or trait symbol, or a file path") symbol: String,
    @description("include kinds and declaring symbols (default false)") detailed: Option[Boolean],
    @description("glob on a member's definition uri; `*` matches any chars")
    pathFilter: Option[String],
    @description("sections to return: any of \"declared\", \"inherited\" (default both)")
    include: Option[List[String]]
) derives Decoder,
      Schema

final case class ResolveImplicitsArgs(
    @description("the wanted type's symbol, e.g. pkg/Show#") `type`: String
) derives Decoder,
      Schema

final case class TraceImplicitChainArgs(
    @description("the wanted type's symbol") `type`: String,
    @description(
      "optional concrete wanted type, e.g. `Show[List[Int]]`; returns one resolved tree when unambiguous"
    )
    appliedType: Option[String]
) derives Decoder,
      Schema

final case class CallPathArgs(
    @description("caller method symbol") from: String,
    @description("callee method symbol") to: String,
    @description("include call-site edge locations (default false)") detailed: Option[Boolean]
) derives Decoder,
      Schema

final case class CallHierarchyArgs(
    @description("method symbol") symbol: String,
    @description(
      "\"callers\" (incoming — who calls this) or \"callees\" (outgoing — what this calls); default \"callees\""
    )
    direction: Option[String],
    @description("how many levels to expand (default 3)") depth: Option[Int]
) derives Decoder,
      Schema

final case class TypeAtPositionArgs(
    @description("document uri as it appears in SemanticDB (path relative to project root)")
    uri: String,
    @description("0-based line") line: Int,
    @description("0-based column") character: Int,
    @description("current full text of the file at `uri`; enables the live PC overlay")
    source: Option[String]
) derives Decoder,
      Schema

final case class SearchTextArgs(
    @description("text (or regex, with `regex: true`) to search for") query: String,
    @description("treat `query` as a regex instead of a literal string (default false)")
    regex: Option[Boolean],
    @description("match case-sensitively (default false)") caseSensitive: Option[Boolean],
    @description("glob on document uri; `*` matches any chars (substring match)")
    pathFilter: Option[String],
    @description("max hits to return across all files (default 200)") limit: Option[Int]
) derives Decoder,
      Schema

/** Descriptions shared by every tool that renders source (one constant per option, so the wording
  * cannot drift between tools).
  */
object ViewDesc:
  inline val Format =
    "annotated (default, gutter + ⟹ notes) | compilable (// ⟹ comments, valid Scala) | plain " +
      "| diff (unified diff, source → enriched, for green/red rendering)"
  inline val AnnotationsOnly =
    "return only the lines that carry an annotation, not the whole file (default false)"
  inline val Symbols =
    "append a legend mapping each type used to its full package path (default false); with " +
      "format=compilable it also explodes wildcard/`given` imports into the explicit names used"
  inline val Docs = "keep (default) | strip — drop comments for a leaner token view"
  inline val Detail =
    "terse (default) | full — render a merged elaborated expression at each call site"
  inline val Sentinel =
    "append each note as a machine-strippable `/*SEM:...:SEM*/` block comment at the end of its line " +
      "instead of a `⟹` note (default false). Reversible: " +
      "`SourceSentinel.strip` (analysis module) removes exactly this block and nothing else — " +
      "real comments elsewhere on the line survive untouched — so edited-and-annotated text can " +
      "be stripped back to plain source before it is persisted."

/** The rendering options the source tools share (`format`, `annotationsOnly`, `symbols`, `docs`,
  * `detail`, `sentinel`); each tool's argument class carries them as its own fields.
  */
trait ViewOpts:
  def format: Option[String]
  def annotationsOnly: Option[Boolean]
  def symbols: Option[Boolean]
  def docs: Option[String]
  def detail: Option[String]
  def sentinel: Option[Boolean]

  final def docsMode: String = docs.getOrElse("")
  final def detailValue: SourceDetail = SourceDetail.from(detail.getOrElse(""))
  final def formatValue: SourceFormat = SourceFormat.from(format.getOrElse(""))
  final def symbolsOn: Boolean = symbols.getOrElse(false)
  final def annotationsOnlyOn: Boolean = annotationsOnly.getOrElse(false)
  final def sentinelOn: Boolean = sentinel.getOrElse(false)

final case class AnnotatedSourceArgs(
    @description("document uri as it appears in SemanticDB (path relative to project root)")
    uri: String,
    @description(
      "the file's edited full text — switches this tool to WRITE mode: /*SEM:...:SEM*/ blocks are " +
        "stripped and the result is saved to uri. Send the buffer you read, edited in place, " +
        "with its /*SEM:...:SEM*/ blocks still where they were — stripping them yourself is " +
        "unnecessary work on lines your edit does not concern. Omit to read instead."
    )
    write: Option[String],
    @description(
      "write mode only: the `sha256` from an earlier read of this uri — the write is rejected " +
        "if the file no longer matches (a concurrent edit happened)."
    )
    baseHash: Option[String],
    @description(ViewDesc.Format) format: Option[String],
    @description(ViewDesc.AnnotationsOnly) annotationsOnly: Option[Boolean],
    @description(ViewDesc.Symbols) symbols: Option[Boolean],
    @description(ViewDesc.Docs) docs: Option[String],
    @description(ViewDesc.Detail) detail: Option[String],
    @description(ViewDesc.Sentinel) sentinel: Option[Boolean]
) extends ViewOpts derives Decoder, Schema

final case class SymbolSourceArgs(
    @description("SemanticDB symbol OR dotted FQN of the definition") symbol: String,
    @description(ViewDesc.Format) format: Option[String],
    @description(ViewDesc.AnnotationsOnly) annotationsOnly: Option[Boolean],
    @description(ViewDesc.Symbols) symbols: Option[Boolean],
    @description(ViewDesc.Docs) docs: Option[String],
    @description(ViewDesc.Detail) detail: Option[String],
    @description(ViewDesc.Sentinel) sentinel: Option[Boolean]
) extends ViewOpts derives Decoder, Schema

final case class SourceAroundPositionArgs(
    @description("document uri (path relative to project root)") file: String,
    @description("0-based line of the position") line: Int,
    @description("0-based column of the position") column: Int,
    @description(ViewDesc.Format) format: Option[String],
    @description(ViewDesc.AnnotationsOnly) annotationsOnly: Option[Boolean],
    @description(ViewDesc.Symbols) symbols: Option[Boolean],
    @description(ViewDesc.Docs) docs: Option[String],
    @description(ViewDesc.Detail) detail: Option[String],
    @description(ViewDesc.Sentinel) sentinel: Option[Boolean]
) extends ViewOpts derives Decoder, Schema

/** `source_ranges` renders enriched slices only, so it exposes just three of the shared options. */
final case class SourceRangesArgs(
    @description("`target[startLine-endLine;...];target2[startLine-endLine]` — see description")
    query: String,
    @description(
      "return only the lines that carry an annotation, not the whole range (default false)"
    )
    annotationsOnly: Option[Boolean],
    @description(ViewDesc.Docs) docs: Option[String],
    @description(
      "terse | full (default) — merge multiple insertions at one call site into one note"
    )
    detail: Option[String]
) extends ViewOpts derives Decoder, Schema:
  val format: Option[String] = None
  val symbols: Option[Boolean] = None
  val sentinel: Option[Boolean] = None

final case class DocumentOutlineArgs(
    @description("document uri as it appears in SemanticDB (path relative to project root)")
    uri: String,
    @description("current full text of the file at `uri`; enables the live PC overlay")
    source: Option[String],
    @description("keep declarations whose name contains this, case-insensitive")
    query: Option[String],
    @description("keep the declaration with exactly this SemanticDB symbol")
    symbol: Option[String],
    @description("keep the enclosing scopes of a match as context (default true)")
    includeParents: Option[Boolean],
    @description("levels of nesting to keep below each match (1 = the match alone)")
    maxDepth: Option[Int],
    @description("keep only this kind, e.g. CLASS, TRAIT, OBJECT, METHOD") kind: Option[String]
) derives Decoder,
      Schema

final case class RenamePlanArgs(
    @description("the symbol to rename") symbol: String,
    @description("the new simple name") newName: String
) derives Decoder,
      Schema

final case class RenameEntryArgs(
    @description("the symbol to rename") symbol: String,
    @description("the new simple name") newName: String
) derives Decoder,
      Schema

final case class BatchRenamePlanArgs(
    @description("list of {symbol: string, newName: string} entries to rename in one batch")
    renames: List[RenameEntryArgs]
) derives Decoder,
      Schema

final case class MovePlanArgs(
    @description("the symbol to move") symbol: String,
    @description("destination package symbol, e.g. `com/foo/bar/`") newOwner: String
) derives Decoder,
      Schema

final case class ExtractMethodPlanArgs(
    @description("document uri (path relative to project root)") uri: String,
    @description("0-based start line of the selection") startLine: Int,
    @description("0-based start column of the selection") startCharacter: Int,
    @description("0-based end line of the selection (exclusive end)") endLine: Int,
    @description("0-based end column of the selection (exclusive)") endCharacter: Int,
    @description("name for the extracted method (default `extracted`)")
    methodName: Option[String],
    @description("current full text of the file at `uri`; enables the live PC overlay")
    source: Option[String]
) derives Decoder,
      Schema

final case class StructureArgs(
    @description(
      "rank symbols by: afferent | efferent | instability | layer | centrality | sccSize (default afferent)"
    )
    sort: Option[String],
    @description(
      "which graph's metrics per symbol: combined | extends | typeRef | memberType | call | implicit (default combined)"
    )
    dimension: Option[String],
    @description("glob on a type's module (e.g. `core`, `*mcp*`) to scope the list")
    pathFilter: Option[String],
    @description("max symbols to return (default 30)") limit: Option[Int],
    @description("include the per-dimension Ca/Ce/instability breakdown (default false)")
    detailed: Option[Boolean],
    @description(
      "include a `mermaid` string field rendering `moduleEdges` as a `graph TD` diagram (default false)"
    )
    graph: Option[Boolean]
) derives Decoder,
      Schema

final case class ValueFlowArgs(
    @description("SemanticDB symbol of the val/binding/parameter to trace")
    symbol: Option[String],
    @description(
      "document uri (relative to project root); with `line`/`column`, an alternative to `symbol`"
    )
    file: Option[String],
    @description("0-based line of the binding (with `file` + `column`)") line: Option[Int],
    @description("0-based column of the binding (with `file` + `line`)") column: Option[Int],
    @description("max flow hops to follow from the root (default 5)") maxDepth: Option[Int],
    @description(
      "do not expand a flow into a binding of a different (widened) type (default true)"
    )
    stopOnTypeWidening: Option[Boolean]
) derives Decoder,
      Schema

final case class SmartCodeDuplicationsArgs(
    @description("minimum number of AST nodes to consider (default 15)") minSize: Option[Int],
    @description("glob on the document uri; `*` matches any chars") pathFilter: Option[String],
    @description(
      "when true, also return `groupsSource`: each group's occurrences with their duplicated " +
        "source lines as a gutter-numbered `snippet` (default false)"
    )
    showSource: Option[Boolean]
) derives Decoder,
      Schema

final case class HoleContextArgs(
    @description("document uri as it appears in SemanticDB (path relative to project root)")
    uri: String,
    @description("0-based line") line: Int,
    @description("0-based column") character: Int,
    @description("current full text of the file at `uri`") source: Option[String]
) derives Decoder,
      Schema

/** One file of a multi-file request, with optional line ranges (`[startLine, endLineExclusive]`,
  * 0-based; absent or empty means the whole file) and optional buffer text.
  */
final case class FileRangesArg(
    @description("document uri (path relative to project root)") uri: String,
    @description("`[startLine, endLineExclusive]` pairs, 0-based; absent or empty = whole file")
    ranges: Option[List[List[Int]]],
    @description("current full text of the file; else the on-disk file is read")
    source: Option[String]
) derives Decoder,
      Schema:
  /** The well-formed `[from, until]` pairs; a range with fewer than two numbers is skipped. */
  def rangePairs: Seq[(Int, Int)] =
    ranges.getOrElse(Nil).flatMap(r => r.lift(0).zip(r.lift(1)))

final case class FileSourceArg(
    @description("document uri (path relative to project root)") uri: String,
    @description("current full text of the file; else the on-disk file is read")
    source: Option[String]
) derives Decoder,
      Schema

final case class LiteralTokensArgs(
    @description("objects: {uri, ranges?: [[startLine, endLineExclusive], …], source?}")
    files: List[FileRangesArg]
) derives Decoder,
      Schema

final case class ResolveReferencesArgs(
    @description("objects: {uri, ranges: [[startLine, endLineExclusive], …], source?}")
    files: List[FileRangesArg]
) derives Decoder,
      Schema

final case class ContractClausesArgs(
    @description("objects: {uri, source?}") files: List[FileSourceArg]
) derives Decoder,
      Schema

final case class SetWorkspaceRootArgs(
    @description("absolute or relative path to the new workspace root") path: String
) derives Decoder,
      Schema

final case class RefreshWorkspaceArgs(
    @description("absolute or relative path to rebuild; defaults to the current workspace root")
    path: Option[String],
    @description(
      "run the auto-detected build's compile task in this directory before rebuilding the index (default false)"
    )
    compile: Option[Boolean]
) derives Decoder,
      Schema

final case class OutputSchemaArgs(
    @description("tool name, e.g. find_usages; omit for every typed tool") tool: Option[String]
) derives Decoder,
      Schema
