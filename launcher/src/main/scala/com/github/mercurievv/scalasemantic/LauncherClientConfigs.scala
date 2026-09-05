package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

private[scalasemantic] object LauncherClientConfigs:
  private val ServerName = "scala-semantic"

  /** `relPath` is resolved against the project, `userPath` against `$HOME`. `userPath = None` means
    * this client has no known user-level MCP config; `--scope user` skips it rather than guessing.
    */
  private final case class Target(
      relPath: String,
      userPath: Option[String],
      fmt: Fmt,
      extraJson: Seq[(String, String)]
  )
  private sealed trait Fmt
  private case object JsonFmt extends Fmt
  private case object TomlFmt extends Fmt
  private case object YamlFmt extends Fmt

  def write(project: Path, opts: LauncherSetup.Options): Unit =
    // Relativization is a project-scope idea (ADR-0002): a committed config should survive a clone.
    // A user-scope config is never shared, and GUI-launched clients often spawn without
    // ~/.local/bin on PATH, so it keeps the absolute launcher path.
    val command = opts.scope match
      case LauncherScope.Project => relativizeCommand(project, opts.command)
      case LauncherScope.User    => opts.command
    val argv = Seq(command, "serve", ".")
    val clients =
      if opts.client.trim.toLowerCase == "all" then
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      else Seq(opts.client)
    clients.foreach { client =>
      (targetFor(client), targetPathFor(opts, client)) match
        case (Some(target), Some(out)) =>
          Option(out.getParent).foreach(Files.createDirectories(_))
          val existing = if Files.exists(out) then Some(Files.readString(out)) else None
          val merged =
            target.fmt match
              case JsonFmt =>
                LauncherConfigMerge.mergeJson(existing, ServerName, argv, target.extraJson)
              case TomlFmt =>
                LauncherConfigMerge.mergeToml(existing, ServerName, argv)
              case YamlFmt =>
                LauncherConfigMerge.mergeYaml(existing, ServerName, argv)
          Files.writeString(out, merged)
          LauncherMessages.err(s"wrote $out")
        case (Some(_), None) =>
          LauncherMessages.err(s"skipped '$client': no user-level MCP config location is known")
        case (None, _) =>
          LauncherMessages.err(s"unsupported client '$client'")
    }

  /** The absolute config file to write for `client` under `opts.scope`, or `None` when that client
    * has no config location in that scope.
    */
  private[scalasemantic] def targetPathFor(
      opts: LauncherSetup.Options,
      client: String
  ): Option[Path] =
    targetFor(client).flatMap { target =>
      opts.scope match
        case LauncherScope.Project => Some(opts.project.resolve(target.relPath))
        case LauncherScope.User    => target.userPath.map(opts.home.resolve)
    }

  /** Clients that ship without a known user-level MCP config; `--scope user` skips them. */
  private[scalasemantic] def clientsWithoutUserScope: Seq[String] =
    Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity", "generic")
      .filter(c => targetFor(c).exists(_.userPath.isEmpty))

  // Relative wins when the launcher script lives inside the project (portable across clones,
  // matches this repo's own .mcp.json); absolute stays as fallback for bare PATH commands or a
  // launcher living outside the project, since some MCP clients spawn the server with cwd != project.
  private[scalasemantic] def relativizeCommand(project: Path, command: String): String =
    val path = Path.of(command)
    if path.isAbsolute then
      val root = project.toAbsolutePath.normalize()
      val abs = path.toAbsolutePath.normalize()
      if abs.startsWith(root) then "./" + root.relativize(abs).toString
      else command
    else command

  private def targetFor(client: String): Option[Target] =
    client.trim.toLowerCase.replace('_', '-') match
      case "codex" | "openai" | "openai-codex" =>
        Some(Target(".codex/config.toml", Some(".codex/config.toml"), TomlFmt, Nil))
      // Claude Code's user-level config is the one that does not mirror its project path.
      case "claude" | "claude-code" | "anthropic" =>
        Some(Target(".mcp.json", Some(".claude.json"), JsonFmt, Nil))
      case "gemini" | "google" | "google-gemini" | "gemini-cli" =>
        Some(
          Target(
            ".gemini/settings.json",
            Some(".gemini/settings.json"),
            JsonFmt,
            Seq("timeout" -> "60000")
          )
        )
      // Antigravity's IDE and CLI share one global file, documented at
      // https://antigravity.google/docs/cli/mcp/ — note it lives under .gemini, not .antigravity.
      case "antigravity" | "antigravity-cli" | "agy" =>
        Some(
          Target(".agents/mcp_config.json", Some(".gemini/config/mcp_config.json"), JsonFmt, Nil)
        )
      // Cline: no user path. The VS Code extension keeps global MCP settings inside VS Code's
      // globalStorage (an OS-specific path, not one $HOME-relative location), and for the CLI the
      // docs and the code disagree on the path (cline/cline#11671). Unconfirmed means None.
      case "cline" =>
        Some(
          Target(
            ".cline/mcp.json",
            None,
            JsonFmt,
            Seq("disabled" -> "false", "autoApprove" -> "[]")
          )
        )
      // Roo: no user path, for the same globalStorage reason as Cline.
      case "roo" | "roo-code" =>
        Some(
          Target(
            ".roo/mcp.json",
            None,
            JsonFmt,
            Seq("disabled" -> "false", "alwaysAllow" -> "[]", "timeout" -> "60")
          )
        )
      case "continue" | "continue-dev" =>
        Some(Target(".continue/config.yaml", Some(".continue/config.yaml"), YamlFmt, Nil))
      case "generic" | "generic-json" | "json" | "oss" | "open-source" | "free" =>
        Some(Target(".mcp.json", None, JsonFmt, Nil))
      case _ => None
