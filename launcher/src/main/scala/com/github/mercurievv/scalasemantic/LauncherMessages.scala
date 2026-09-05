package com.github.mercurievv.scalasemantic

private[scalasemantic] object LauncherMessages:
  def usage(exit: Int): Nothing =
    Console.err.println(
      s"""|Usage:
          |  scalasemantic-mcp setup [--scope user|project] [--client claude|codex|gemini|cline|roo|continue|antigravity|all] [--project DIR] [--rwhook-local|--rwhook-user|--rw-hook-remove] [--strict-edits]
          |  scalasemantic-mcp doctor [--project DIR]
          |  scalasemantic-mcp serve <semanticdb-root> [classpath-file] [--log] [--log-output]
          |
          |doctor re-runs, and reports in words, every condition the guard hook checks before it is
          |willing to deny a text tool: hook installed, up to date and registered, MCP server
          |configured, a SemanticDB index emitted, a JSON reader on PATH. Exits 1 when the guard is
          |installed but would fail open. setup runs the same check at the end of an install.
          |
          |--scope project (default) writes config into the project directory and also configures
          |SemanticDB and the tool rules file. --scope user writes only the MCP registration, into
          |the per-user config of each client that has one. Neither installs the guard hook: that
          |is what the --rwhook flags below are for, and they say for themselves where it goes.
          |
          |For Claude Code there is a PreToolUse guard hook (${LauncherGuardHook.HookRelPath}) that
          |denies text tools on .scala sources and routes them to the MCP tools instead. It is NOT
          |installed by default -- it changes how every later session in that directory behaves, so
          |it has to be asked for:
          |  --rwhook-local    install it for this project (.claude/ in the project)
          |  --rwhook-user     install it for this user (~/.claude/), covering every project
          |  --rw-hook-remove  remove it from both, leaving the rest of settings.json alone
          |A plain setup run installs nothing, but does keep a hook that is already there up to
          |date. Editing a Scala source is allowed under the hook, with a reminder to edit the
          |annotated buffer instead (annotated_source with format=compilable, sentinel=true, then
          |write=); --strict-edits turns that reminder into a denial, and implies --rwhook-local.
          |
          |Setup writes MCP client config that launches the same lightweight shell launcher:
          |  command = ${sys.env.getOrElse("SCALASEMANTIC_LAUNCHER", "scalasemantic-mcp")}
          |  args    = [serve, .]
          |""".stripMargin
    )
    sys.exit(exit)

  def err(message: String): Unit =
    Console.err.println(s"scalasemantic-mcp: $message")
