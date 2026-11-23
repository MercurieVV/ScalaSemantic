//> using scala 3.8.4
// Moves the API result/argument types from mcp into wire.api (ADR 0006 follow-up) and keeps the
// schema-attachment objects (which need OutputSchema) in mcp/ToolSchemas.scala.
// Usage: scala-cli run move-api-types.sc -- <repoRoot>
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

val repo = Paths.get(args(0))
val mcpDir = repo.resolve("mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp")
val apiDir = repo.resolve("wire/src/main/scala/com/github/mercurievv/scalasemantic/wire/api")
Files.createDirectories(apiDir)

def lines(p: Path): List[String] = Files.readAllLines(p).asScala.toList

// 1. split the three mixed files: head = types (moves), tail = schema objects (stays in mcp)
val mixed = List(
  "SourceToolResults" -> "object Alternatives:",
  "PlanToolResults" -> "object PlanToolSchemas:",
  "AnalysisToolResults" -> "object AnalysisToolSchemas:"
)
val tails = mixed.map { (name, marker) =>
  val p = mcpDir.resolve(s"$name.scala")
  val ls = lines(p)
  val idx = ls.indexWhere(_.startsWith(marker))
  require(idx >= 0, s"$name: marker '$marker' not found")
  // include a directly preceding doc comment
  val start = Iterator.iterate(idx)(_ - 1).takeWhile(_ > 0).find(i => !ls(i - 1).trim.startsWith("*") && !ls(i - 1).trim.startsWith("/**") && !ls(i - 1).trim.startsWith("*/")).getOrElse(idx)
  val imports = ls.filter(_.startsWith("import "))
  Files.writeString(p, ls.take(start).mkString("\n").stripTrailing() + "\n")
  (imports, ls.drop(start).mkString("\n"))
}

val toolSchemas =
  "package com.github.mercurievv.scalasemantic.mcp\n\n" +
    tails.flatMap(_._1).distinct.sorted.mkString("\n") + "\n\n" +
    "// Attaches the `outputSchema` of the tools whose bodies still build JSON by hand. The result\n" +
    "// types themselves live in `wire.api`; only the attachment needs `OutputSchema` and so stays here.\n\n" +
    tails.map(_._2.stripTrailing()).mkString("\n\n") + "\n"
Files.writeString(mcpDir.resolve("ToolSchemas.scala"), toolSchemas)

// 2. rewrite package + visibility, then move
val moved = List(
  "FindSymbolResult", "FindUsagesResult", "MethodSignatureResult", "ClassHierarchyResult",
  "MembersResult", "ResolveImplicitsResult", "CallGraphResults", "RecursiveSchemas",
  "ToolResultParts", "ToolArgs", "SourceToolResults", "PlanToolResults", "AnalysisToolResults"
)
moved.foreach { n =>
  val src = mcpDir.resolve(s"$n.scala")
  val text = Files.readString(src)
    .replace("package com.github.mercurievv.scalasemantic.mcp\n", "package com.github.mercurievv.scalasemantic.wire.api\n")
    .replace("private[mcp] ", "")
  Files.writeString(apiDir.resolve(s"$n.scala"), text)
  Files.delete(src)
}
println(s"moved ${moved.size} files to wire.api; wrote mcp/ToolSchemas.scala")
