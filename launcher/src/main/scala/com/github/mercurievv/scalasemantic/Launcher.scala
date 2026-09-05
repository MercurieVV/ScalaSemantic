package com.github.mercurievv.scalasemantic

private[scalasemantic] object Launcher:
  def run(rawArgs: Seq[String])(serve: Seq[String] => Unit): Unit =
    val args = rawArgs.toList match
      case "--" :: rest => rest
      case other        => other

    args.headOption match
      case Some("setup" | "configure" | "install") =>
        LauncherSetup.setup(args.drop(1).toList)
      case Some("doctor" | "check") =>
        LauncherDoctor.run(args.drop(1).toList)
      case Some("serve" | "run") =>
        serve(args.drop(1).toList)
      case Some("--help" | "-h" | "help") =>
        LauncherMessages.usage(0)
      case _ =>
        serve(args.toList)
