package com.github.mercurievv.scalasemantic

private[scalasemantic] object Launcher:
  // Two callbacks rather than an import of `mcp`: `launcher` must not depend on `mcp` (see the
  // module graph in build.mill), so the one-shot `call` entry point is handed in by `Main`, same as
  // `serve`. `call` defaults to a no-op so existing single-callback call sites (tests exercising
  // `setup`/`doctor`, which never reach the `call` branch) keep compiling unchanged.
  def run(rawArgs: Seq[String])(
      serve: Seq[String] => Unit,
      call: Seq[String] => Unit = _ => ()
  ): Unit =
    val args = rawArgs.toList match
      case "--" :: rest => rest
      case other        => other

    args.headOption match
      case Some("setup" | "configure" | "install") =>
        LauncherSetup.setup(args.drop(1).toList)
      case Some("doctor" | "check") =>
        LauncherDoctor.run(args.drop(1).toList)
      case Some("call") =>
        call(args.drop(1).toList)
      case Some("serve" | "run") =>
        serve(args.drop(1).toList)
      case Some("--help" | "-h" | "help") =>
        LauncherMessages.usage(0)
      case _ =>
        serve(args.toList)
