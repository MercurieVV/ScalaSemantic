package com.github.mercurievv.scalasemantic

import com.github.mercurievv.scalasemantic.mcp.Mcp
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Start the MCP server over stdio. Usage: `runMain com.github.mercurievv.scalasemantic.mcpServer
  * [semanticdbRoot] [classpath]`.
  *   - `semanticdbRoot` (default `.`): where it recursively finds emitted `*.semanticdb` files.
  *   - `classpath` (optional): the target project's compile classpath — a path-separated string or
  *     a file containing one — which enables the presentation-compiler backend for live overlay of
  *     uncompiled buffers (the tools' `source` argument). Also read from `SCALASEMANTIC_CLASSPATH`.
  */
@main def mcpServer(args: String*): Unit =
  Mcp.serve(args.headOption.getOrElse("."), args.drop(1).headOption)

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
