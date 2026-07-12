package scalasemantic.docs

/** Runs a real ScalaSemantic tool by shelling to the 3.8.4 assembly jar and returns its exact JSON.
  * Paths come from system properties set by DocsMain (see build.mill forkArgs). Used from mdoc
  * fences, so it must depend only on os-lib and upickle (3.3-compatible). Any non-zero exit throws
  * → the docs build fails loudly.
  */
object ToolRunner:
  private def jar = sys.props("scalasemantic.docs.toolCliJar")
  private def index = sys.props("scalasemantic.docs.indexDir")
  private def root = sys.props.getOrElse("scalasemantic.docs.root", ".")

  /** Field names whose value is source-code-shaped even on a single line (no embedded `\n`), so
    * they're always pulled into their own fenced `scala` block by [[resultMarkdown]] instead of
    * being left to plain (unhighlighted) JSON.
    */
  private val codeFields = Set("source", "signature")

  def run(tool: String, args: String): String =
    os
      .proc(
        "java",
        "-cp",
        jar,
        "com.github.mercurievv.scalasemantic.mcp.ToolCli",
        "--index",
        index,
        "--root",
        root,
        "--tool",
        tool,
        "--args",
        args
      )
      .call(check = true)
      .out
      .text()
      .trim

  /** Modified-buffer variant: passes the edited file text + its uri. */
  def runWithSource(tool: String, args: String, uri: String, sourcePath: String): String =
    os
      .proc(
        "java",
        "-cp",
        jar,
        "com.github.mercurievv.scalasemantic.mcp.ToolCli",
        "--index",
        index,
        "--root",
        root,
        "--tool",
        tool,
        "--args",
        args,
        "--uri",
        uri,
        "--source",
        sourcePath
      )
      .call(check = true)
      .out
      .text()
      .trim

  /** The tool's own `description` field — the same string an MCP client sees via `tools/list` —
    * instead of a hand-maintained doc blurb that can drift from what the server actually reports.
    */
  def describe(tool: String): String =
    os
      .proc(
        "java",
        "-cp",
        jar,
        "com.github.mercurievv.scalasemantic.mcp.ToolCli",
        "--index",
        index,
        "--root",
        root,
        "--tool",
        tool,
        "--describe"
      )
      .call(check = true)
      .out
      .text()
      .trim

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
    requestMarkdown(tool, args) + "\n\n" + resultMarkdown(run(tool, args))

  def runWithSourcePretty(tool: String, args: String, uri: String, sourcePath: String): String =
    requestMarkdown(tool, args) + "\n\n" + resultMarkdown(
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

  /** Pulls a single field's value out of a tool's raw JSON, unwrapped (no markdown fencing) so the
    * caller can place it inside a custom layout (e.g. a diff panel).
    */
  def extractField(raw: String, field: String): String =
    ujson.read(raw).obj(field).str

  /** Collapsed `<details>` block with the raw JSON, eliding fields already shown elsewhere (e.g. as
    * an extracted code block via [[extractField]]) so nothing is printed twice.
    */
  def detailsMarkdown(raw: String, elideFields: Seq[String] = Nil): String =
    val parsed = ujson.read(raw)
    val elided = ujson.Obj.from(parsed.obj.toSeq.map { case (k, v) =>
      if elideFields.contains(k) then k -> ujson.Str("(shown above)") else k -> v
    })
    val redactedJson = ujson.write(elided, indent = 2)
    s"""<details>
       |<summary>Raw JSON</summary>
       |
       |```json
       |$redactedJson
       |```
       |
       |</details>""".stripMargin

  private def resultMarkdown(raw: String): String =
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
      s"$codeBlocks\n\n${detailsMarkdown(raw, codeLikeFields.map(_._1))}"
