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

  /** Markdown for a tool's JSON result: any top-level string field that carries embedded newlines
    * (e.g. `annotated_source`'s `source`) is pulled into its own fenced Scala block — multi-line
    * and readable — instead of sitting escaped as `\n` inside one JSON line. The full JSON (with
    * those fields elided, since they're already shown above) follows in a collapsed `<details>` so
    * the extracted code stays the visually primary content.
    */
  def runPretty(tool: String, args: String): String =
    prettyMarkdown(tool, args, run(tool, args))

  def runWithSourcePretty(tool: String, args: String, uri: String, sourcePath: String): String =
    prettyMarkdown(tool, args, runWithSource(tool, args, uri, sourcePath))

  /** `**Request:**` block listing the tool name and its input params as plain `name: value` bullets
    * (not a JSON blob — params are data, not code, so a formatted list reads faster), shown ahead
    * of the output so the reader sees what was asked before what was answered.
    */
  private def requestMarkdown(tool: String, args: String): String =
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

  private def prettyMarkdown(tool: String, args: String, raw: String): String =
    requestMarkdown(tool, args) + "\n\n" + resultMarkdown(raw)

  private def resultMarkdown(raw: String): String =
    val parsed = ujson.read(raw)
    val multilineFields = parsed.obj.toSeq.collect {
      case (k, ujson.Str(v)) if v.contains("\n") =>
        k -> v
    }
    if multilineFields.isEmpty then s"```json\n$raw\n```"
    else
      val codeBlocks = multilineFields
        .map { case (k, v) => s"**`$k`:**\n\n```scala\n$v\n```" }
        .mkString("\n\n")
      val elided = ujson.Obj.from(parsed.obj.toSeq.map { case (k, v) =>
        if multilineFields.exists(_._1 == k) then k -> ujson.Str("(shown above)") else k -> v
      })
      val redactedJson = ujson.write(elided, indent = 2)
      s"""$codeBlocks
         |
         |<details>
         |<summary>Raw JSON</summary>
         |
         |```json
         |$redactedJson
         |```
         |
         |</details>""".stripMargin
