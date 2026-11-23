//> using scala 3.8.4
// Mechanical ujson -> circe/J port for the mcp module (ADR 0006, stage C).
// Usage: scala-cli run port-ujson.sc -- <file>...
// Leaves `case ujson.Str(x)` patterns and `.value` accesses for a manual pass (the compiler finds them).
import java.nio.file.{Files, Paths}

val rules: List[(String, String)] = List(
  """ujson\.Obj\.from\(""" -> "J.objOf(",
  """ujson\.Arr\.from\(""" -> "J.arrOf(",
  """(?<!case )ujson\.Obj\(""" -> "J.obj(",
  """(?<!case )ujson\.Arr\(""" -> "J.arr(",
  """(?<!case )ujson\.Str\(""" -> "J.str(",
  """(?<!case )ujson\.Num\(""" -> "J.num(",
  """(?<!case )ujson\.Bool\(""" -> "J.bool(",
  """ujson\.Null\b""" -> "Json.Null",
  """ujson\.Value\b""" -> "Json",
  """ujson\.Obj\b(?!\()""" -> "Json",
  """ujson\.Arr\b(?!\()""" -> "Json",
  """ujson\.read\(""" -> "J.read(",
  """ujson\.write\(""" -> "J.write("
)

args.foreach { f =>
  val p = Paths.get(f)
  val before = Files.readString(p)
  val after = rules.foldLeft(before)((s, r) => s.replaceAll(r._1, java.util.regex.Matcher.quoteReplacement(r._2)))
  if after != before then
    val pkgMcp = after.linesIterator.exists(_.startsWith("package com.github.mercurievv.scalasemantic.mcp"))
    val needs = List(
      Option.when(after.contains("Json"))("import io.circe.Json"),
      Option.when(after.contains("J."))(
        if pkgMcp then "import com.github.mercurievv.scalasemantic.mcp.JOps.*"
        else "import com.github.mercurievv.scalasemantic.mcp.J\nimport com.github.mercurievv.scalasemantic.mcp.JOps.*"
      )
    ).flatten.filterNot(after.contains)
    val withImports =
      if needs.isEmpty then after
      else
        val lines = after.linesIterator.toList
        val pkgIdx = lines.indexWhere(_.startsWith("package "))
        (lines.take(pkgIdx + 1) ++ List("") ++ needs ++ lines.drop(pkgIdx + 1)).mkString("\n") + "\n"
    Files.writeString(p, withImports)
    println(s"ported $f")
}
