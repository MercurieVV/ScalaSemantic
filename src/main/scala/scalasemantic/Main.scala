package scalasemantic

import scalasemantic.mcp.Mcp
import scalasemantic.semanticdb.SemanticIndex

/** Start the MCP server over stdio. Usage: `runMain scalasemantic.mcpServer [semanticdbRoot]` (root
  * defaults to the current directory, where it recursively finds emitted `*.semanticdb` files).
  */
@main def mcpServer(args: String*): Unit =
  Mcp.serve(args.headOption.getOrElse("."))

/** Throwaway entrypoint: load a project's SemanticDB and print a summary. Usage: `run
  * [semanticdbRoot]` (defaults to `target`, i.e. dogfood on this build).
  */
@main def main(args: String*): Unit =
  val root = args.headOption.getOrElse("target")
  val idx = SemanticIndex.fromProject(root)
  println(s"Loaded ${idx.documents.size} documents, ${idx.symbols.size} symbols")
  idx.symbols.keys.toVector.sorted
    .filter(_.startsWith("scalasemantic"))
    .take(30)
    .foreach(sym => println(s"  $sym  name=${idx.displayName(sym)} owner=${idx.owner(sym)}"))
