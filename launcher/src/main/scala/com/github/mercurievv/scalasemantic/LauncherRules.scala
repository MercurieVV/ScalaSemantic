package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

private[scalasemantic] object LauncherRules:
  def ensure(project: Path, client: String): Unit =
    val rulesFile = project.resolve("SCALA_SEMANTIC_RULES.md")
    if !Files.exists(rulesFile) then
      Files.writeString(
        rulesFile,
        """|# Scala Semantic Rules
           |
           |For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.
           |
           |Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type, signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are available.
           |
           |Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.
           |
           |In Claude Code this rule is enforced, not merely advised: the `.claude/hooks/scala-semantic-guard.sh` PreToolUse hook denies Read/Grep/Glob and shell text tools that target `.scala` files. If the semantic tools genuinely cannot answer, re-run the command through Bash with a trailing `# semantic-fallback: <reason>` marker — allowed, and logged to `.claude/semantic-fallback.log`.
           |""".stripMargin
      )
      LauncherMessages.err(s"created $rulesFile")

    val clients =
      if client.trim.toLowerCase == "all" then
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      else Seq(client)
    clients.foreach(c => writeSteerFile(project, c))

  private def writeSteerFile(project: Path, client: String): Unit =
    val normalized = client.trim.toLowerCase.replace('_', '-')
    val target =
      normalized match
        case "claude" | "claude-code" | "anthropic" =>
          Some(project.resolve("CLAUDE.md") -> "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)")
        case "gemini" | "google" | "google-gemini" | "gemini-cli" | "antigravity" |
            "antigravity-cli" | "agy" =>
          Some(project.resolve("AGENTS.md") -> "@SCALA_SEMANTIC_RULES.md")
        case "codex" | "openai" | "openai-codex" =>
          Some(
            project.resolve(".cursorrules") -> "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)"
          )
        case "cline" | "roo" | "roo-code" =>
          Some(
            project.resolve(".clinerules") -> "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)"
          )
        case "continue" | "continue-dev" =>
          Some(project.resolve(".continue").resolve("rules.txt") -> "SCALA_SEMANTIC_RULES.md")
        case _ => None

    target.foreach { case (file, reference) =>
      if Files.exists(file) then
        val current = Files.readString(file)
        if current.contains("SCALA_CODE_RULES.md") then
          Files.writeString(file, current.replace("SCALA_CODE_RULES.md", "SCALA_SEMANTIC_RULES.md"))
          LauncherMessages.err(s"updated $file")
        else if !current.contains("SCALA_SEMANTIC_RULES.md") then
          val sep = if current.endsWith("\n") then "" else "\n"
          Files.writeString(
            file,
            current + sep + s"\n## Scala Code Rules\nPlease follow the rules in $reference.\n"
          )
          LauncherMessages.err(s"updated $file")
      else
        Files.createDirectories(file.getParent)
        val content =
          if file.getFileName.toString == "AGENTS.md" then s"""|# AGENTS.md instructions
                |
                |<INSTRUCTIONS>
                |$reference
                |</INSTRUCTIONS>
                |""".stripMargin
          else
            s"# Project Rules\n\nPlease follow the rules in $reference for working with Scala code.\n"
        Files.writeString(file, content)
        LauncherMessages.err(s"created $file")
    }
