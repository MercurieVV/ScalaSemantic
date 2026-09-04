package scalasemantic.docs

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.McpTools
import com.github.mercurievv.scalasemantic.mcp.Tool
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

/** Runs a real ScalaSemantic tool in the same JVM as the mdoc fence (no subprocess) and returns its
  * exact JSON. Paths come from system properties set by DocsMain (see build.mill forkArgs). The mcp
  * module is a direct compile dependency of this module (see `docs.moduleDeps` in build.mill), so
  * `tool.run(args)` here is the identical code path the MCP server itself calls — the packaged
  * assembly jar's own correctness is covered by mcp's own tests, not this page.
  */
object ToolRunner:
  private def indexDir = sys.props("scalasemantic.docs.indexDir")
  private def structureIndexDirs =
    sys.props
      .get("scalasemantic.docs.structureIndexDirs")
      .orElse(sys.props.get("scalasemantic.docs.structureIndexDir"))
      .getOrElse(indexDir)
      .split(java.io.File.pathSeparator)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .toSeq
  private def root: Path = Paths.get(sys.props.getOrElse("scalasemantic.docs.root", "."))

  private lazy val index = SemanticIndex.fromRoots(Seq(Paths.get(indexDir)))
  private lazy val baseTools: List[Tool] = McpTools.all(Analyzer(index), root)
  private lazy val structureIndex = SemanticIndex.fromRoots(structureIndexDirs)
  private lazy val structureTools: List[Tool] = McpTools.all(Analyzer(structureIndex), root)

  private def toolByName(tools: List[Tool], name: String): Tool =
    tools
      .find(_.name == name)
      .getOrElse(sys.error(s"unknown tool: $name (have: ${tools.map(_.name).mkString(",")})"))

  /** Large source payload fields are lifted out into their own highlighted block by
    * [[resultMarkdown]]. Smaller semantic strings such as `symbol`, `type`, and `signature` stay in
    * SemanticJson, where they are highlighted inline without duplicating the JSON shape.
    */
  private val extractedSourceFields = Set("source")

  def run(tool: String, args: String): String =
    ujson.write(toolByName(baseTools, tool).run(ujson.read(args)), indent = 2)

  def runStructure(args: String): String =
    ujson.write(toolByName(structureTools, "structure").run(ujson.read(args)), indent = 2)

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
    * the compiler's `// ⟹` insertions rather than diffing whole lines. Base64-encoded, like
    * [[wordDiffComponent]], so arbitrary source text (parens, brackets, `⟹`) can never be misparsed
    * by mdoc/MDX's own markdown parser (e.g. a stray `(n)` read as a link reference).
    */
  def enrichedComponent(raw: String): String =
    val encoded = Base64.getEncoder.encodeToString(
      extractField(raw, "source").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    s"<EnrichedCode base64=\"$encoded\" />"

  def syntaxComponent(code: String, language: String = "scala"): String =
    s"<SyntaxCode language=\"$language\" code={${ujson.write(code)}} />"

  def semanticSymbolComponent(symbol: String): String =
    s"<SemanticSymbol value={${ujson.write(symbol)}} />"

  def semanticJsonComponent(json: String): String =
    val encoded =
      Base64.getEncoder.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"<SemanticJson base64=\"$encoded\" />"

  def mermaidComponent(kind: String, chart: String): String =
    val encoded =
      Base64.getEncoder.encodeToString(chart.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"<MermaidDiagram kind=\"$kind\" base64=\"$encoded\" />"

  def outlineTreeComponent(raw: String): String =
    val encoded =
      Base64.getEncoder.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"<OutlineTree base64=\"$encoded\" />"

  def structureGraphComponent(raw: String): String =
    val encoded =
      Base64.getEncoder.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"<StructureGraph base64=\"$encoded\" />"

  def wordDiffComponent(diff: String): String =
    val encoded =
      Base64.getEncoder.encodeToString(diff.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"<WordDiffCode base64=\"$encoded\" />"

  /** `source_ranges`' own `NNNNN  ` gutter prefix, stripped so its `source` lines compare cleanly
    * against the real file's plain lines.
    */
  private def stripGutter(line: String): String =
    line.replaceFirst("^\\s*\\d+  ", "")

  /** A line-diff (`wordDiffComponent`-ready) between the real original lines of `requestedRange`
    * (read fresh from `uri`, the same "1-4" shape `source_ranges` echoes back) and one of its
    * results' own enriched `source` — a docs-presentation aid only: `source_ranges` itself never
    * computes or returns a diff, its `source` field is enriched code alone. This exists so the docs
    * page can still show what changed, without asking the tool to be two things at once. Changed
    * lines are shown as a whole removed `-` line followed by a whole added `+` line (unified-diff
    * style), not an intraline word/char diff — a changed line is usually rewritten almost entirely,
    * so highlighting the few surviving characters reads as noise rather than signal.
    */
  def sourceRangesDiff(uri: String, requestedRange: String, enrichedSource: String): String =
    val bounds = requestedRange.split("-", 2)
    val startLine = bounds(0).toInt
    val endLine = bounds(1).toInt
    val original = readSource(uri).split("\n", -1).toList.slice(startLine - 1, endLine)
    val enriched = enrichedSource.split("\n", -1).toList.map(stripGutter)
    val header = s"--- $uri (original)\n+++ $uri (enriched)"
    val count = original.size
    val hunkHeader = s"@@ -$startLine,$count +$startLine,$count @@"
    val lines =
      original.zip(enriched).flatMap { (o, n) =>
        if o == n then List(s" $o") else List(s"-$o", s"+$n")
      }
    s"$header\n$hunkHeader\n${lines.mkString("\n")}"

  /** Render a `document_outline` result's nested `outline` array as a Mermaid `graph TD` tree: one
    * node per member (label = name + kind; signature shown when present), edges parent->child. Node
    * ids are sequential (`n0`, `n1`, ...) to stay valid regardless of symbol characters.
    */
  def outlineMermaid(raw: String): String =
    outlineTreeComponent(raw)

  def callHierarchyMermaid(raw: String): String =
    val root = ujson.read(raw)
    def esc(s: String): String =
      s.replace("\"", "&quot;")
    def shortLocation(at: String): String =
      at.split('/').lastOption.getOrElse(at)
    def label(node: ujson.Value): String =
      val name = node.obj.get("name").map(_.str).getOrElse("?")
      val at = node.obj.get("at").map(v => s"\\n${shortLocation(v.str)}").getOrElse("")
      esc(s"$name$at")
    def walk(parentId: Option[String], node: ujson.Value, next: Int): (List[String], Int) =
      val id = s"n$next"
      val own =
        List(s"""  $id["${label(node)}"]""") ++ parentId.map(p => s"  $p --> $id").toList
      val children = node.obj.get("children").map(_.arr.toList).getOrElse(Nil)
      children.foldLeft((own, next + 1)) { case ((lines, counter), child) =>
        val (childLines, childNext) = walk(Some(id), child, counter)
        (lines ++ childLines, childNext)
      }
    val hierarchy = root.obj("hierarchy")
    val (lines, _) = walk(None, hierarchy, 0)
    mermaidComponent("call-hierarchy", s"graph TD\n${lines.mkString("\n")}")

  /** Render a concrete `trace_implicit_chain` result's nested `resolved` tree as Mermaid. The raw
    * JSON keeps the full candidate set; the graph makes the chosen dependency path visible at a
    * glance.
    */
  def implicitTreeMermaid(raw: String): String =
    val root = ujson.read(raw)
    root.obj.get("resolved") match
      case None => mermaidComponent("implicit", "graph TD\n  n0[\"No concrete resolution\"]")
      case Some(resolved) =>
        def esc(s: String): String =
          s.replace("\"", "&quot;")
        def shortSymbol(symbol: String): String =
          symbol
            .stripSuffix(".")
            .stripSuffix("()")
            .stripSuffix("#")
            .split("[/#.]")
            .filter(_.nonEmpty)
            .lastOption
            .getOrElse(symbol)
        def label(node: ujson.Value): String =
          val targetType = node.obj.get("type").map(_.str).getOrElse("?")
          val chosen = node.obj.get("chosen").map(v => shortSymbol(v.str))
          val suffix =
            if node.obj.get("cycle").exists(_.bool) then "\\ncycle"
            else if node.obj.get("ambiguous").exists(_.bool) then "\\nambiguous"
            else chosen.fold("\\nno match")(c => s"\\n$c")
          esc(s"$targetType$suffix")
        def walk(parentId: Option[String], node: ujson.Value, next: Int): (List[String], Int) =
          val id = s"n$next"
          val own =
            List(s"""  $id["${label(node)}"]""") ++ parentId.map(p => s"  $p --> $id").toList
          val children = node.obj.get("children").map(_.arr.toList).getOrElse(Nil)
          children.foldLeft((own, next + 1)) { case ((lines, counter), child) =>
            val (childLines, childNext) = walk(Some(id), child, counter)
            (lines ++ childLines, childNext)
          }
        val (lines, _) = walk(None, resolved, 0)
        mermaidComponent("implicit", s"graph TD\n${lines.mkString("\n")}")

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
       |${semanticJsonComponent(inputJson)}
       |
       |Result:
       |${semanticJsonComponent(outputJson)}
       |
       |</details>""".stripMargin

  private def resultMarkdown(args: String, raw: String): String =
    val parsed = ujson.read(raw)
    val extractedFields = parsed.obj.toSeq.collect {
      case (k, ujson.Str(v)) if v.contains("\n") && extractedSourceFields.contains(k) =>
        k -> v
    }
    if extractedFields.isEmpty then semanticJsonComponent(raw)
    else
      val codeBlocks = extractedFields
        .map { case (k, v) => s"**`$k`:**\n\n${syntaxComponent(v)}" }
        .mkString("\n\n")
      List(codeBlocks, detailsMarkdown(args, raw)).filter(_.nonEmpty).mkString("\n\n")
