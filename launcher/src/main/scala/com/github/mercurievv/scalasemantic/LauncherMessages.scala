package com.github.mercurievv.scalasemantic

private[scalasemantic] object LauncherMessages:
  def usage(exit: Int): Nothing =
    Console.err.println(
      s"""|Usage:
          |  scalasemantic-mcp setup [--scope user|project] [--client claude|codex|gemini|cline|roo|continue|antigravity|all] [--project DIR] [--no-guard]
          |  scalasemantic-mcp serve <semanticdb-root> [classpath-file] [--log] [--log-output]
          |
          |--scope project (default) writes config into the project directory and also configures
          |SemanticDB, the tool rules file and the Claude guard hook. --scope user writes only the
          |MCP registration, into the per-user config of each client that has one.
          |
          |For Claude Code, setup also installs a PreToolUse guard hook denying text tools on .scala
          |sources (${LauncherGuardHook.HookRelPath}); pass --no-guard to skip it.
          |
          |Setup writes MCP client config that launches the same lightweight shell launcher:
          |  command = ${sys.env.getOrElse("SCALASEMANTIC_LAUNCHER", "scalasemantic-mcp")}
          |  args    = [serve, .]
          |""".stripMargin
    )
    sys.exit(exit)

  def err(message: String): Unit =
    Console.err.println(s"scalasemantic-mcp: $message")
