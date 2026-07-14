package scalasemantic.docs

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.McpTools
import com.github.mercurievv.scalasemantic.mcp.Tool
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Runs a real ScalaSemantic tool in the same JVM as the mdoc fence (no subprocess) and returns its
  * exact JSON. Paths come from system properties set by DocsMain (see build.mill forkArgs). The mcp
  * module is a direct compile dependency of this module (see `docs.moduleDeps` in build.mill), so
  * `tool.run(args)` here is the identical code path the MCP server itself calls — the packaged
  * assembly jar's own correctness is covered by mcp's own tests, not this page.
  */
object ToolRunner:
  private def indexDir = sys.props("scalasemantic.docs.indexDir")
  private def root: Path = Paths.get(sys.props.getOrElse("scalasemantic.docs.root", "."))

  private lazy val index = SemanticIndex.fromRoots(Seq(Paths.get(indexDir)))
  private lazy val baseTools: List[Tool] = McpTools.all(Analyzer(index), root)

  private def toolByName(tools: List[Tool], name: String): Tool =
    tools
      .find(_.name == name)
      .getOrElse(sys.error(s"unknown tool: $name (have: ${tools.map(_.name).mkString(",")})"))

  /** Field names whose value is source-code-shaped even on a single line (no embedded `\n`), so
    * they're always pulled into their own fenced `scala` block by [[resultMarkdown]] instead of
    * being left to plain (unhighlighted) JSON.
    */
  private val codeFields = Set("source", "signature")

  def run(tool: String, args: String): String =
    ujson.write(toolByName(baseTools, tool).run(ujson.read(args)), indent = 2)

  /** Modified-buffer variant: passes the edited file text + its uri through the presentation
    * compiler, exactly as ToolCli's `--source` branch does.
    */
  def runWithSource(tool: String, args: String, uri: String, sourcePath: String): String =
    val sourceText = new String(Files.readAllBytes(Paths.get(sourcePath)))
    val argsJson = ujson.read(args)
    argsJson("source") = sourceText
    argsJson("uri") = uri
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val tools = McpTools.all(Analyzer(index, Some(backend)), root)
      ujson.write(toolByName(tools, tool).run(argsJson), indent = 2)
    }

  /** Reads a fixture file relative to the docs root — the same file a tool call's `uri`/`source`
    * argument points at, so a "source under analysis" block and a tool's own output are always
    * printing the exact same bytes, never a hand-copied (and driftable) duplicate.
    */
  def readSource(relativePath: String): String =
    os.read(os.Path(root, os.pwd) / os.RelPath(relativePath)).stripLineEnd

  /** Markdown for a tool's JSON result: any top-level field that's source-code-shaped (either it
    * carries embedded newlines, e.g. `annotated_source`'s `source`, or its name is in
    * [[codeFields]], e.g. `method_signature`'s `signature`) is pulled into its own fenced Scala
    * block — highlighted, readable — instead of sitting escaped/unhighlighted inside JSON. The
    * remaining fields (references, symbols, counts — plain data, not code) stay as formatted JSON
    * in a collapsed `<details>` so the extracted code stays the visually primary content.
    */
  def runPretty(tool: String, args: String): String =
    requestMarkdown(tool, args) + "\n\n" + resultMarkdown(args, run(tool, args))

  def runWithSourcePretty(tool: String, args: String, uri: String, sourcePath: String): String =
    requestMarkdown(tool, args) + "\n\n" + resultMarkdown(
      args,
      runWithSource(tool, args, uri, sourcePath)
    )

  /** `**Request:**` block listing the tool name and its input params as plain `name: value` bullets
    * (not a JSON blob — params are data, not code, so a formatted list reads faster), shown ahead
    * of the output so the reader sees what was asked before what was answered.
    */
  def requestMarkdown(tool: String, args: String): String =
    val argLines = scala.util.Try(ujson.read(args)) match
      case scala.util.Success(obj: ujson.Obj) if obj.obj.nonEmpty =>
        obj.obj.toSeq
          .map { case (k, v) =>
            val value = v match
              case ujson.Str(s) => s
              case other        => ujson.write(other)
            s"- **`$k`**: `$value`"
          }
          .mkString("\n")
      case _ => "*(no parameters)*"
    s"""**Request:** `$tool`
       |
       |**Arguments:**
       |
       |$argLines""".stripMargin

  /** Request header shown ONCE above a group of tabs: the tool name and the args that are constant
    * across every tab. Pass only the fixed args as a ujson object string.
    */
  def commonRequestMarkdown(tool: String, fixedArgs: String): String =
    requestMarkdown(tool, fixedArgs)

  /** One-line note of just the arg(s) that make THIS tab differ from the common request. */
  def variantLine(label: String, changedArgs: String): String =
    val changed = ujson.read(changedArgs) match
      case obj: ujson.Obj =>
        obj.obj.toSeq
          .map { case (k, v) =>
            val value = v match
              case ujson.Str(s) => s
              case other        => ujson.write(other)
            s"`$k` = `$value`"
          }
          .mkString(", ")
      case _ => "(defaults)"
    s"**$label** — $changed"

  /** Pulls a single field's value out of a tool's raw JSON, unwrapped (no markdown fencing) so the
    * caller can place it inside a custom layout (e.g. a diff panel).
    */
  def extractField(raw: String, field: String): String =
    ujson.read(raw).obj(field).str

  /** A `<EnrichedCode>` MDX element wrapping a tool result's `source` field — the intra-line
    * insert-tinting component (see `website/src/components/EnrichedCode.js`), which highlights only
    * the compiler's `// ⟹` insertions rather than diffing whole lines. The source is passed as a
    * JSON-encoded string prop so backticks, braces, and the `⟹` glyph survive MDX parsing intact.
    */
  def enrichedComponent(raw: String): String =
    s"<EnrichedCode code={${ujson.write(extractField(raw, "source"))}} />"

  /** Render a `document_outline` result's nested `outline` array as a Mermaid `graph TD` tree: one
    * node per member (label = name + kind; signature shown when present), edges parent->child. Node
    * ids are sequential (`n0`, `n1`, ...) to stay valid regardless of symbol characters.
    */
  def outlineMermaid(raw: String): String =
    val root = ujson.read(raw)
    def esc(s: String): String =
      s.replace("\"", "&quot;").replace("[", "&#91;").replace("]", "&#93;")
    def label(node: ujson.Value): String =
      val name = node.obj.get("name").map(_.str).getOrElse("?")
      val kind = node.obj.get("kind").map(_.str).getOrElse("")
      val sig = node.obj.get("signature").map(_.str).filter(_.nonEmpty)
      val head = s"$name : $kind"
      esc(sig.fold(head)(s => s"$head\\n$s"))
    def walk(parentId: Option[String], node: ujson.Value, next: Int): (List[String], Int) =
      val id = s"n$next"
      val own = List(s"""  $id["${label(node)}"]""") ++ parentId.map(p => s"  $p --> $id").toList
      val children = node.obj.get("children").map(_.arr.toList).getOrElse(Nil)
      children.foldLeft((own, next + 1)) { case ((lines, counter), child) =>
        val (childLines, childNext) = walk(Some(id), child, counter)
        (lines ++ childLines, childNext)
      }
    val (lines, _) = root.obj("outline").arr.toList.foldLeft((List.empty[String], 0)) {
      case ((acc, counter), node) =>
        val (nodeLines, next) = walk(None, node, counter)
        (acc ++ nodeLines, next)
    }
    s"```mermaid\ngraph TD\n${lines.mkString("\n")}\n```"

  /** Collapsed `<details>` block with the raw JSON, eliding fields already shown elsewhere (e.g. as
    * an extracted code block via [[extractField]]) so nothing is printed twice.
    */
  def detailsMarkdown(input: String, output: String): String =
    val inputJson = ujson.write(ujson.read(input), indent = 2)
    val outputJson = ujson.write(ujson.read(output), indent = 2)

    s"""<details>
       |<summary>Raw JSON</summary>
       |
       |Arguments:
       |```json
       |$inputJson
       |```
       |
       |Result:
       |```json
       |$outputJson
       |```
       |
       |</details>""".stripMargin

  private def resultMarkdown(args: String, raw: String): String =
    val parsed = ujson.read(raw)
    val codeLikeFields = parsed.obj.toSeq.collect {
      case (k, ujson.Str(v)) if v.contains("\n") || codeFields.contains(k) =>
        k -> v
    }
    if codeLikeFields.isEmpty then s"```json\n$raw\n```"
    else
      val codeBlocks = codeLikeFields
        .map { case (k, v) => s"**`$k`:**\n\n```scala\n$v\n```" }
        .mkString("\n\n")
      s"$codeBlocks\n\n${detailsMarkdown(args, raw)}"
