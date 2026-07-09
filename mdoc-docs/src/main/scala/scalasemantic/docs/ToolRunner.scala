package scalasemantic.docs

/** Runs a real ScalaSemantic tool by shelling to the 3.8.4 assembly jar and returns its exact JSON.
  * Paths come from system properties set by DocsMain (see build.mill forkArgs). Used from mdoc
  * fences, so it must depend only on os-lib (3.3-compatible). Any non-zero exit throws → the docs
  * build fails loudly.
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
