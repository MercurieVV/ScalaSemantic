/** Renders the mdoc documentation for the Docusaurus microsite. The sbt-mdoc plugin has no sbt 2.0
  * build, so we drive the mdoc library directly: it compiles + executes the Scala fences in the
  * Markdown under `docs`, then writes the result into `website/docs`. So every snippet's output
  * is real and the docs cannot rot. (mdoc's snippet compiler does not support the main build's Scala
  * version, so this `docs` module is standalone and the snippets are illustrative rather than
  * in-process analyzer calls — see docs/DESIGN.md.)
  *
  * Usage: `sbt docs/run` (regenerate), or `sbt "docs/run --watch"` (live).
  */
object DocsMain:
  def main(args: Array[String]): Unit =
    val settings = mdoc
      .MainSettings()
      .withIn(java.nio.file.Paths.get("docs"))
      .withOut(java.nio.file.Paths.get("website", "docs"))
      .withClasspath(System.getProperty("java.class.path"))
      .withArgs(args.toList)
    val exitCode = mdoc.Main.process(settings)
    if exitCode != 0 then sys.exit(exitCode)
