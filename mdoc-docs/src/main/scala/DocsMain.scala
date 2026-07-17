/** Renders the mdoc documentation for the Docusaurus microsite. The sbt-mdoc plugin has no sbt 2.0
  * build, so we drive the mdoc library directly: it compiles + executes the Scala fences in the
  * Markdown under `docs`, then writes the result into `website/docs`. So every snippet's output is
  * real and the docs cannot rot. `ToolRunner` (in this module) calls the `mcp` module in-process —
  * this `docs` module is standalone only because mdoc's own transitive compiler dep must be pinned
  * to the main build's Scala version (see the `scala3-compiler` override in build.mill).
  *
  * Usage: `sbt docs/run` (regenerate), or `sbt "docs/run --watch"` (live).
  */
object DocsMain:
  def main(args: Array[String]): Unit =
    // Latest release version, passed by the build as a -D property (falls back to a placeholder).
    // Registered as the mdoc `@VERSION@` site variable so version snippets fill in at build time.
    val version = Option(System.getProperty("scalasemantic.docs.version")).getOrElse("x.y.z")
    val settings = mdoc
      .MainSettings()
      .withIn(java.nio.file.Paths.get("docs"))
      .withOut(java.nio.file.Paths.get("website", "docs"))
      .withClasspath(System.getProperty("java.class.path"))
      .withSiteVariables(Map("VERSION" -> version))
      .withArgs(args.toList)
    val exitCode = mdoc.Main.process(settings)
    if exitCode != 0 then sys.exit(exitCode)

    // Opt-in MDX: mdoc only EXECUTES `*.md` (it copies `*.mdx` verbatim, unrendered), while
    // Docusaurus's `detect` only PARSES `*.mdx` as MDX (our `*.md` pages are CommonMark on purpose —
    // they contain `<root>`, `List[A]`, `{...}` MDX would choke on). A page that needs MDX (JSX
    // components like <Tabs>/<EnrichedCode>) therefore declares `format: mdx` in its front matter;
    // here — after rendering — we rename those generated outputs to `*.mdx` so Docusaurus treats
    // them as MDX. Bridges the two tools' opposite extension conventions.
    val outDir = java.nio.file.Paths.get("website", "docs")
    val mdxPages = scala.collection.mutable.ListBuffer.empty[java.nio.file.Path]
    val generated = java.nio.file.Files.walk(outDir)
    try
      generated
        .filter((p: java.nio.file.Path) => p.toString.endsWith(".md"))
        .filter((p: java.nio.file.Path) =>
          java.nio.file.Files
            .readString(p, java.nio.charset.StandardCharsets.UTF_8)
            .linesIterator
            .take(6)
            .contains("format: mdx")
        )
        .forEach((p: java.nio.file.Path) => mdxPages += p)
    finally generated.close()

    mdxPages.foreach { (p: java.nio.file.Path) =>
      val target = java.nio.file.Paths.get(p.toString.stripSuffix(".md") + ".mdx")
      val _ = java.nio.file.Files.move(
        p,
        target,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
    }
