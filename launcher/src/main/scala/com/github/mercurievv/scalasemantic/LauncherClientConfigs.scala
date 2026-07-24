package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

private[scalasemantic] object LauncherClientConfigs:
  private val ServerName = "scala-semantic"

  private final case class Target(relPath: String, fmt: Fmt, extraJson: Seq[(String, String)])
  private sealed trait Fmt
  private case object JsonFmt extends Fmt
  private case object TomlFmt extends Fmt
  private case object YamlFmt extends Fmt

  def write(project: Path, opts: LauncherSetup.Options): Unit =
    val argv = Seq(opts.command.getOrElse(LauncherSetup.DefaultCommand), "serve", ".")
    val clients =
      if opts.client.trim.toLowerCase == "all" then
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      else Seq(opts.client)
    clients.foreach { client =>
      targetFor(client) match
        case Some(target) =>
          val out = project.resolve(target.relPath)
          Files.createDirectories(out.getParent)
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
        case None =>
          LauncherMessages.err(s"unsupported client '$client'")
    }

  private def targetFor(client: String): Option[Target] =
    client.trim.toLowerCase.replace('_', '-') match
      case "codex" | "openai" | "openai-codex" =>
        Some(Target(".codex/config.toml", TomlFmt, Nil))
      case "claude" | "claude-code" | "anthropic" =>
        Some(Target(".mcp.json", JsonFmt, Nil))
      case "gemini" | "google" | "google-gemini" | "gemini-cli" =>
        Some(Target(".gemini/settings.json", JsonFmt, Seq("timeout" -> "60000")))
      case "antigravity" | "antigravity-cli" | "agy" =>
        Some(Target(".agents/mcp_config.json", JsonFmt, Nil))
      case "cline" =>
        Some(Target(".cline/mcp.json", JsonFmt, Seq("disabled" -> "false", "autoApprove" -> "[]")))
      case "roo" | "roo-code" =>
        Some(
          Target(
            ".roo/mcp.json",
            JsonFmt,
            Seq("disabled" -> "false", "alwaysAllow" -> "[]", "timeout" -> "60")
          )
        )
      case "continue" | "continue-dev" =>
        Some(Target(".continue/config.yaml", YamlFmt, Nil))
      case "generic" | "generic-json" | "json" | "oss" | "open-source" | "free" =>
        Some(Target(".mcp.json", JsonFmt, Nil))
      case _ => None
