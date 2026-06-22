package com.github.mercurievv.scalasemantic

import com.github.mercurievv.scalasemantic.mcp.Mcp
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Start the MCP server over stdio. Usage: `runMain com.github.mercurievv.scalasemantic.mcpServer
  * [semanticdbRoot] [classpath] [--log] [--log-output]`.
  *   - `semanticdbRoot` (default `.`): where it recursively finds emitted `*.semanticdb` files.
  *   - `classpath` (optional): the target project's compile classpath — a path-separated string or
  *     a file containing one — which enables the presentation-compiler backend for live overlay of
  *     uncompiled buffers (the tools' `source` argument). Also read from `SCALASEMANTIC_CLASSPATH`.
  *
  * Logging is OFF by default (no log file is written). Enable via flags — passed straight through
  * the launcher from your `.mcp.json` args — or the matching env vars:
  *   - `--log`        (or `SCALASEMANTIC_LOG=1`): diagnostic log (startup + per-tool-call lines).
  *   - `--log-output` (or `SCALASEMANTIC_LOG_OUTPUT=1`): also log each JSON-RPC response sent to
  *     the LLM. Implies a sink, so it works on its own. Flags are positional-order-independent.
  */
@main def mcpServer(args: String*): Unit =
  val (flags, positional) = args.partition(_.startsWith("--"))
  def envOn(name: String): Boolean =
    sys.env.get(name).map(_.trim.toLowerCase).exists(Set("1", "true", "yes", "on"))
  val logOutputs = flags.contains("--log-output") || envOn("SCALASEMANTIC_LOG_OUTPUT")
  val logEnabled = flags.contains("--log") || envOn("SCALASEMANTIC_LOG") || logOutputs
  Mcp.serve(
    positional.headOption.getOrElse("."),
    positional.drop(1).headOption,
    Mcp.LogConfig(enabled = logEnabled, logOutputs = logOutputs)
  )

/** Throwaway entrypoint: load a project's SemanticDB and print a summary. Usage: `run
  * [semanticdbRoot]` (defaults to `target`, i.e. dogfood on this build).
  */
@main def main(args: String*): Unit =
  val root = args.headOption.getOrElse("target")
  val idx = SemanticIndex.fromProject(root)
  println(s"Loaded ${idx.documents.size} documents, ${idx.symbols.size} symbols")
  idx.symbols.keys.toVector.sorted
    .filter(_.startsWith("com/github/mercurievv/scalasemantic"))
    .take(30)
    .foreach(sym => println(s"  $sym  name=${idx.displayName(sym)} owner=${idx.owner(sym)}"))
