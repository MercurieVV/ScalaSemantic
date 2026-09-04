package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Where an install registers the server: in one project's config files, or in the per-user config
  * of every client that has one. See ADR-0004.
  */
private[scalasemantic] enum LauncherScope:
  case User, Project

private[scalasemantic] object LauncherSetup:
  final case class Options(
      project: Path = Path.of(".").toAbsolutePath.normalize(),
      client: String = "all",
      command: String = sys.env.getOrElse("SCALASEMANTIC_LAUNCHER", "scalasemantic-mcp"),
      skipSemanticdbConfig: Boolean = false,
      guard: Boolean = true,
      scope: LauncherScope = LauncherScope.Project,
      home: Path = Path.of(sys.props.getOrElse("user.home", ".")).toAbsolutePath.normalize()
  )

  def setup(rawArgs: List[String]): Unit =
    val opts = parse(rawArgs)
    opts.scope match
      // User scope registers the server and nothing else: SemanticDB config, the rules file and the
      // guard hook all configure a project, and a user-scope install has no project to configure.
      case LauncherScope.User =>
        LauncherClientConfigs.write(opts.project, opts)
      case LauncherScope.Project =>
        val project = opts.project
        Files.createDirectories(project)
        ensureSemanticdbConfig(project, opts.skipSemanticdbConfig)
        LauncherRules.ensure(project, opts.client)
        LauncherClientConfigs.write(project, opts)
        if opts.guard then LauncherGuardHook.install(project, opts.client)
        ensureClasspathMetadataDir(project)

  private[scalasemantic] def parse(args: List[String]): Options =
    @tailrec def loop(rest: List[String], opts: Options): Options =
      rest match
        case Nil                                       => opts
        case ("--project" | "--root") :: value :: tail =>
          loop(tail, opts.copy(project = Path.of(value).toAbsolutePath.normalize()))
        case ("--client" | "-c") :: value :: tail =>
          loop(tail, opts.copy(client = value))
        case "--command" :: value :: tail =>
          loop(tail, opts.copy(command = value))
        case "--skip-semanticdb-config" :: tail =>
          loop(tail, opts.copy(skipSemanticdbConfig = true))
        case "--no-guard" :: tail =>
          loop(tail, opts.copy(guard = false))
        case "--guard" :: tail =>
          loop(tail, opts.copy(guard = true))
        case "--scope" :: value :: tail =>
          val scope = value.trim.toLowerCase match
            case "user"    => LauncherScope.User
            case "project" => LauncherScope.Project
            case bad       =>
              LauncherMessages.err(s"unknown --scope value: $bad (expected user or project)")
              LauncherMessages.usage(2)
          loop(tail, opts.copy(scope = scope))
        case ("--help" | "-h") :: _ =>
          LauncherMessages.usage(0)
        case bad :: _ =>
          LauncherMessages.err(s"unknown setup argument: $bad")
          LauncherMessages.usage(2)
    loop(args, Options())

  private def ensureSemanticdbConfig(project: Path, skip: Boolean): Unit =
    if !skip then
      val sbtFiles =
        Using.resource(Files.list(project)) { stream =>
          stream
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".sbt"))
            .toVector
        }
      val hasBuildMill =
        Files.exists(project.resolve("build.mill")) || Files.exists(project.resolve("build.sc"))
      val hasProjectScala = Files.exists(project.resolve("project.scala"))
      val hasScalaFiles = hasSuffix(project, ".scala") || hasSuffix(project, ".sc")

      if sbtFiles.nonEmpty then ensureSemanticdbSbt(project, sbtFiles)
      else if !hasBuildMill && (hasProjectScala || hasScalaFiles) then
        ensureSemanticdbScalacli(project)
        writeScalaCliClasspath(project, "scala-cli")
      else if hasBuildMill then
        LauncherMessages.err(
          "scalasemantic-mcp: Mill project detected. Setup will generate MCP configurations, but make sure to run 'mill scalaSemanticWriteClasspath' or let compileClasspath run in build.mill."
        )
      else
        LauncherMessages.err(
          "no build files found; enable SemanticDB manually before using ScalaSemantic"
        )

  private def ensureSemanticdbSbt(project: Path, sbtFiles: Vector[Path]): Unit =
    val classpathConfigured =
      sbtFiles.exists(p => Files.readString(p).contains("scalaSemanticWriteClasspath"))
    if !classpathConfigured then
      val file = project.resolve("scala-semantic.sbt")
      Files.writeString(file, SbtSemanticdbConfig.content)
      LauncherMessages.err(s"created $file")
    else
      val alreadyConfigured =
        sbtFiles.exists(p => Files.readString(p).contains("semanticdbEnabled"))
      if !alreadyConfigured then
        val file = project.resolve("scala-semantic.sbt")
        val existing = if Files.exists(file) then Files.readString(file) else ""
        Files.writeString(file, existing + "\nThisBuild / semanticdbEnabled := true\n")
        LauncherMessages.err(s"updated $file")

  private def ensureSemanticdbScalacli(project: Path): Unit =
    val files =
      Using.resource(Files.list(project)) { stream =>
        stream
          .iterator()
          .asScala
          .filter(p =>
            Files.isRegularFile(
              p
            ) && (p.getFileName.toString == "project.scala" || p.getFileName.toString
              .endsWith(".scala") || p.getFileName.toString.endsWith(".sc"))
          )
          .toVector
      }
    val alreadyConfigured =
      files.exists(p => {
        val s = Files.readString(p)
        s.contains("semanticdb") || s.contains("Ysemanticdb")
      })
    if !alreadyConfigured then
      val file = project.resolve("project.scala")
      if !Files.exists(file) then
        val is213 =
          files.exists(f => Files.readString(f).contains("using scala \"2."))
        val content =
          if is213 then """|// Generated by ScalaSemantic MCP setup.
               |//> using plugin "org.scalameta:::semanticdb-scalac:4.13.9"
               |//> using options "-Yrangepos" "-P:semanticdb:sourceroot:."
               |""".stripMargin
          else """|// Generated by ScalaSemantic MCP setup.
               |//> using options "-Ysemanticdb" "-sourceroot" "."
               |""".stripMargin
        Files.writeString(file, content)
        LauncherMessages.err(s"created $file")

  private def writeScalaCliClasspath(project: Path, command: String): Unit =
    try
      val cmd = Seq(command, "compile", "--print-class-path", project.toString)
      val classpathString = scala.sys.process.Process(cmd, project.toFile).!!
      val entries = classpathString
        .split(java.io.File.pathSeparator)
        .map(_.trim)
        .filter(_.nonEmpty)
        .distinct
        .toVector
      val scalaVersion = entries
        .flatMap { entry =>
          val filename = Path.of(entry).getFileName.toString
          if filename.startsWith("scala3-library_3-") then
            val parts = filename.stripPrefix("scala3-library_3-").stripSuffix(".jar").split("-")
            if parts.length >= 1 then Some(parts(0)) else None
          else if filename.startsWith("scala-library-") then
            Some(filename.stripPrefix("scala-library-").stripSuffix(".jar"))
          else None
        }
        .headOption
        .getOrElse("3.8.4")

      val cpEntries = entries
        .map(e => "        " + scalaSemanticJsonString(scalaSemanticRel(project, Path.of(e))))
        .mkString(",\n")
      val content =
        "{\n" +
          "  \"schemaVersion\": 1,\n" +
          "  \"buildTool\": \"scala-cli\",\n" +
          "  \"modules\": [\n" +
          "    {\n" +
          "      \"id\": \"root\",\n" +
          "      \"baseDir\": \".\",\n" +
          "      \"scalaVersion\": " + scalaSemanticJsonString(scalaVersion) + ",\n" +
          "      \"configuration\": \"Compile\",\n" +
          "      \"classpath\": [\n" +
          cpEntries + "\n" +
          "      ]\n" +
          "    }\n" +
          "  ]\n" +
          "}\n"

      val outDir = project.resolve(".scala-semantic")
      Files.createDirectories(outDir)
      val out = outDir.resolve("classpath-scala-cli.json")
      Files.writeString(out, content)
      LauncherMessages.err(s"generated Scala CLI classpath metadata: $out")
    catch
      case e: Exception =>
        LauncherMessages.err(
          s"warning: failed to run '$command compile --print-class-path': ${e.getMessage}"
        )

  private def ensureClasspathMetadataDir(project: Path): Unit =
    Files.createDirectories(project.resolve(".scala-semantic"))

  private def hasSuffix(project: Path, suffix: String): Boolean =
    Using.resource(Files.list(project)) { stream =>
      stream
        .iterator()
        .asScala
        .exists(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(suffix))
    }

  private def scalaSemanticJsonString(value: String): String =
    "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => "\\u%04x".format(c.toInt)
      case c            => c.toString
    } + "\""

  private def scalaSemanticRel(root: Path, file: Path): String =
    val rootPath = root.toAbsolutePath.normalize()
    val path = file.toAbsolutePath.normalize()
    if path.startsWith(rootPath) then rootPath.relativize(path).toString else path.toString
