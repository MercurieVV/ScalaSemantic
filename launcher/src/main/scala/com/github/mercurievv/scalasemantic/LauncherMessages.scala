package com.github.mercurievv.scalasemantic

private[scalasemantic] object LauncherMessages:
  def usage(exit: Int): Nothing =
    Console.err.println(
      s"""|Usage:
          |  scalasemantic-mcp setup [--scope user|project] [--client claude|codex|gemini|cline|roo|continue|antigravity|all] [--project DIR] [--no-guard] [--strict-edits]
          |  scalasemantic-mcp doctor [--project DIR]
          |  scalasemantic-mcp serve <semanticdb-root> [classpath-file] [--log] [--log-output]
          |
          |doctor re-runs, and reports in words, every condition the guard hook checks before it is
          |willing to deny a text tool: hook installed, up to date and registered, MCP server
          |configured, a SemanticDB index emitted, a JSON reader on PATH. Exits 1 when the guard is
          |installed but would fail open. setup runs the same check at the end of an install.
          |
          |--scope project (default) writes config into the project directory and also configures
          |SemanticDB, the tool rules file and the Claude guard hook. --scope user writes only the
          |MCP registration, into the per-user config of each client that has one.
          |
          |For Claude Code, setup also installs a PreToolUse guard hook denying text tools on .scala
          |sources (${LauncherGuardHook.HookRelPath}); pass --no-guard to skip it. Editing a Scala
          |source is allowed but reminds the agent to edit the annotated buffer instead
          |(annotated_source with format=compilable, sentinel=true, then write=); --strict-edits
          |turns that reminder into a denial.
          |
          |Setup writes MCP client config that launches the same lightweight shell launcher:
          |  command = ${sys.env.getOrElse("SCALASEMANTIC_LAUNCHER", "scalasemantic-mcp")}
          |  args    = [serve, .]
          |""".stripMargin
    )
    sys.exit(exit)

  def err(message: String): Unit =
    Console.err.println(s"scalasemantic-mcp: $message")
