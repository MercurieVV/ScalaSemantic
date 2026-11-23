//> using scala 3.8.4
// Compiles a Mill target repeatedly, deleting every import the compiler reports as unused
// (-Werror makes them errors, which blocks scalafix — the chicken-and-egg after a mass move).
// Usage: scala-cli run prune-unused-imports.sc -- <repoRoot> <millTarget>   e.g. mcp.compile
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

val repo = args(0)
val target = args(1)
val ansi = "\u001b\\[[0-9;]*m".r
val head = """.*\[error\] (\S+\.scala):(\d+):\d+\s*$""".r

def build(): List[String] =
  val out = new StringBuilder
  Process(Seq("./mill", target), new java.io.File(repo)).!(ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n')))
  ansi.replaceAllIn(out.toString, "").linesIterator.toList

var round = 0
var done = false
while !done && round < 25 do
  round += 1
  val ls = build()
  val unused = ls.zipWithIndex.collect { case (head(f, n), i) if ls.slice(i + 1, i + 6).exists(_.trim.endsWith("unused import")) => (f, n.toInt) }.distinct
  if unused.isEmpty then
    val errs = ls.count(_.contains("[error]"))
    println(s"round $round: no unused-import errors left; other error lines: $errs")
    ls.filter(_.contains("[error]")).take(12).foreach(println)
    done = true
  else
    unused.groupBy(_._1).foreach { (f, hits) =>
      val p = Paths.get(repo).resolve(f)
      val src = Files.readAllLines(p).asScala.toList
      val drop = hits.map(_._2 - 1).toSet
      Files.write(p, src.zipWithIndex.filterNot((_, i) => drop(i)).map(_._1).asJava)
    }
    println(s"round $round: removed ${unused.size} unused import(s)")
