package scalasemantic

import scalasemantic.semanticdb.SemanticIndex

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
