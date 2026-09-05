#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// A LOOK-AT-IT demo of the annotation-aware read/write path. Not a test — it prints every stage so
// you can see with your own eyes that the buffer an agent edits is annotated, while the file that
// lands on disk is not.
//
//   ./mill mcp.assembly
//   scala-cli scripts/demo-annotated-write.sc
//
// Modes:
//   (no args)                          self-contained demo on a throwaway fixture project
//   --file Foo.scala                   annotate one of YOUR files and print it — read only
//   --file Foo.scala --replace a=>b    ...and actually write the edited buffer back
//   --project DIR                      workspace root for --file (default: .)
//   --keep                             leave the fixture sandbox on disk and print its path
//   --no-color                         plain output for piping
//
// Env: SCALASEMANTIC_JAR overrides the server jar (default out/mcp/assembly.dest/out.jar).

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object DemoAnnotatedWrite {

  // --- terminal ------------------------------------------------------------------------------

  private var color = true
  private val Esc = 27.toChar.toString
  def c(code: String, s: String): String = if (color) s"$Esc[${code}m$s$Esc[0m" else s
  def bold(s: String): String = c("1", s)
  def dim(s: String): String = c("2", s)
  def cyan(s: String): String = c("36", s)
  def green(s: String): String = c("32", s)
  def red(s: String): String = c("31", s)
  def yellow(s: String): String = c("33", s)

  def step(n: Int, title: String): Unit = {
    println()
    println(bold(s"── STEP $n ─ $title ") + bold("─" * math.max(0, 66 - title.length)))
    println()
  }

  /** Prints source with a line-number gutter, SEM blocks highlighted so they cannot be missed. */
  def show(text: String, highlight: Boolean = true): Unit = {
    val Sem = """/\*SEM:.*?:SEM\*/""".r
    text.linesIterator.zipWithIndex.foreach { case (line, i) =>
      val body = if (highlight) Sem.replaceAllIn(line, m => cyan(m.matched).replace("\\", "\\\\")) else line
      println(dim(f"${i + 1}%4d │ ") + body)
    }
  }

  def verdict(ok: Boolean, msg: String): Boolean = {
    println(if (ok) green(s"  ✓ $msg") else red(s"  ✗ $msg"))
    ok
  }

  def fail(msg: String): Nothing = { System.err.println(red(s"error: $msg")); sys.exit(1) }

  // --- process plumbing ----------------------------------------------------------------------

  def run(cmd: Seq[String], cwd: Path): (Int, String, String) = {
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    val proc = pb.start()
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    (proc.waitFor(), out, err)
  }

  val Jar: Path =
    Paths.get(sys.env.getOrElse("SCALASEMANTIC_JAR", "out/mcp/assembly.dest/out.jar")).toAbsolutePath

  /** One batch of tools/call requests against a real server process, over real stdio JSON-RPC —
    * exactly the wire an agent uses. Returns the parsed responses.
    */
  def rpc(root: Path, calls: Seq[ujson.Value]): Seq[ujson.Value] = {
    val init =
      """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
        """"capabilities":{},"clientInfo":{"name":"demo","version":"0"}}}"""
    val lines = init +: calls.zipWithIndex.map { case (call, i) =>
      ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> (i + 1), "method" -> "tools/call", "params" -> call))
    }
    val cmd = Seq("java", "-cp", Jar.toString, "com.github.mercurievv.scalasemantic.mcpServer", root.toString)
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(root.toFile)
    val proc = pb.start()
    proc.getOutputStream.write(lines.mkString("", "\n", "\n").getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    if (proc.waitFor() != 0) fail(s"server exited non-zero\n--- stdout ---\n$out\n--- stderr ---\n$err")
    out.linesIterator.filter(_.trim.nonEmpty).map(ujson.read(_)).toSeq
  }

  def call(name: String, args: (String, ujson.Value)*): ujson.Value =
    ujson.Obj("name" -> name, "arguments" -> ujson.Obj.from(args))

  /** The MCP envelope: result.content[0].text carries the tool's own JSON — except on a refusal,
    * where it is a plain-English message. Both are interesting here, so wrap the latter rather
    * than failing on it.
    */
  def payload(msg: ujson.Value): ujson.Value =
    msg.obj.get("result") match {
      case Some(r) if r.obj.contains("content") =>
        val text = r("content")(0)("text").str
        scala.util.Try(ujson.read(text)).getOrElse(ujson.Obj("written" -> false, "message" -> text))
      case _ =>
        msg.obj.get("error") match {
          case Some(e) => ujson.Obj("written" -> false, "message" -> ujson.write(e))
          case None    => fail(s"unexpected response: ${ujson.write(msg)}")
        }
    }

  def annotatedRead(root: Path, uri: String): (String, String) = {
    val resp = rpc(root, Seq(call("annotated_source", "uri" -> uri, "format" -> "compilable", "sentinel" -> true)))
    val p = payload(resp.last)
    if (!p.obj.get("found").forall(_.bool))
      fail(s"$uri is not in the index — compile the project first, then re-run.\n${ujson.write(p)}")
    (p("source").str, p("sha256").str)
  }

  def annotatedWrite(root: Path, uri: String, text: String, baseHash: String): ujson.Value =
    payload(rpc(root, Seq(call("annotated_source", "uri" -> uri, "write" -> text, "baseHash" -> baseHash))).last)

  // --- fixture -------------------------------------------------------------------------------

  /** A small but genuinely inference-heavy source: an inferred return type, a lambda whose param
    * type is inferred, and a `max` that only resolves through the given Ordering. Those are the
    * three things the annotated buffer shows and the raw text does not.
    */
  val FixtureSource: String =
    """|object Fixture:
       |  given byLength: Ordering[String] = Ordering.by(s => s.length)
       |
       |  def sizes(xs: List[String]) = xs.map(s => s.length)
       |
       |  def longest(xs: List[String]) = xs.max
       |
       |  val total = sizes(List("a", "bb", "ccc")).sum
       |""".stripMargin

  def fixtureProject(parent: Path): Path = {
    val dir = Files.createDirectories(parent.resolve("demo-project"))
    Files.writeString(dir.resolve("project.scala"), "//> using scala 3.8.4\n")
    Files.writeString(dir.resolve("Fixture.scala"), FixtureSource)
    compile(dir)
    dir
  }

  /** SemanticIndex skips hidden directories, so the target root must be visible — not the
    * .scala-build/ default.
    */
  def compile(dir: Path): Unit = {
    val (code, out, err) = run(
      Seq("scala-cli", "compile", "--semanticdb", "--semanticdb-sourceroot", ".",
        "--semanticdb-targetroot", "semanticdb", "."),
      dir
    )
    if (code != 0) fail(s"fixture failed to compile\n--- stdout ---\n$out\n--- stderr ---\n$err")
  }

  def rmTree(p: Path): Unit =
    if (Files.exists(p)) Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  /** Naive line diff — enough to see what the write changed. */
  def diff(before: String, after: String): Unit = {
    val b = before.linesIterator.toVector
    val a = after.linesIterator.toVector
    (0 until math.max(b.length, a.length)).foreach { i =>
      val ol = b.lift(i).getOrElse("")
      val nl = a.lift(i).getOrElse("")
      if (ol != nl) {
        if (ol.nonEmpty) println(red(s"  - $ol"))
        if (nl.nonEmpty) println(green(s"  + $nl"))
      }
    }
  }

  // --- the demo ------------------------------------------------------------------------------

  /** Read, edit the annotated buffer, write it back, then prove what did and did not reach disk.
    * `replace` is the edit an agent would make, applied to the ANNOTATED text.
    */
  def roundtrip(root: Path, uri: String, replace: Option[(String, String)]): Boolean = {
    val file = root.resolve(uri)
    val rawBefore = Files.readString(file)

    step(1, "The file as it sits on disk")
    show(rawBefore, highlight = false)
    println()
    println(dim("  Plain text. No types, no implicits — what a text tool would hand you."))

    step(2, "annotated_source(format=compilable, sentinel=true)  ← what the agent READS")
    val (buffer, hash) = annotatedRead(root, uri)
    show(buffer)
    println()
    println(dim(s"  sha256 = $hash"))
    println(dim("  The " + cyan("/*SEM:...:SEM*/") + dim(" blocks are inferred types, implicit arguments and")))
    println(dim("  conversions the compiler resolved. They are comments, so this still compiles."))

    replace match {
      case None =>
        println()
        println(yellow("  Read-only mode — pass --replace old=new to see the write half."))
        true

      case Some((from, to)) =>
        if (!buffer.contains(from)) fail(s"--replace source text not found in the buffer: '$from'")
        val edited = buffer.replace(from, to)

        step(3, s"Edit the annotated buffer: '$from' -> '$to'  (SEM blocks left in place)")
        show(edited)
        println()
        println(dim("  An agent edits THIS. It has the compiler's view in front of it while doing so."))

        step(4, "annotated_source(write=<edited buffer>, baseHash=…)  ← what the agent WRITES")
        val res = annotatedWrite(root, uri, edited, hash)
        println(dim("  server response: ") + ujson.write(res))
        if (!res.obj.get("written").exists(_.bool)) fail(s"write was refused: ${ujson.write(res)}")

        step(5, "The file on disk NOW")
        val rawAfter = Files.readString(file)
        show(rawAfter, highlight = false)
        println()
        println(bold("  changed lines:"))
        diff(rawBefore, rawAfter)

        step(6, "Checks")
        val ok = Seq(
          verdict(buffer.contains("SEM:"), "the buffer the agent read WAS annotated"),
          verdict(!rawAfter.contains("SEM:"), "no SEM sentinel reached disk"),
          verdict(!rawAfter.contains("⟹"), "no ⟹ annotation reached disk"),
          verdict(rawAfter.contains(to), s"the edit ('$to') did reach disk"),
          verdict(
            stripBlank(rawAfter) == stripBlank(sansSem(edited)),
            "disk content == edited buffer minus annotations, byte for byte"
          )
        ).forall(identity)

        val stale = annotatedWrite(root, uri, edited, hash)
        val refused = !stale.obj.get("written").exists(_.bool)
        println()
        println(dim("  replaying the same write with the now-stale baseHash:"))
        println(dim("  " + ujson.write(stale)))
        ok && verdict(refused, "a stale baseHash is refused, so a concurrent edit cannot be clobbered")
    }
  }

  def sansSem(s: String): String = """/\*SEM:.*?:SEM\*/""".r.replaceAllIn(s, "")
  def stripBlank(s: String): String = s.linesIterator.map(_.replaceAll("\\s+$", "")).mkString("\n").trim

  def main(args: Array[String]): Unit = {
    var project = Paths.get(".")
    var file = Option.empty[String]
    var replace = Option.empty[(String, String)]
    var keep = false

    def parse(rest: List[String]): Unit = rest match {
      case Nil                             => ()
      case "--project" :: v :: t           => project = Paths.get(v); parse(t)
      case "--file" :: v :: t              => file = Some(v); parse(t)
      case "--keep" :: t                   => keep = true; parse(t)
      case "--no-color" :: t               => color = false; parse(t)
      case "--replace" :: v :: t =>
        v.split("=>", 2) match {
          case Array(from, to) => replace = Some(from.trim -> to.trim)
          case _               => fail(s"--replace expects 'old=>new', got '$v'")
        }
        parse(t)
      case ("--help" | "-h") :: _ =>
        println("usage: demo-annotated-write.sc [--project DIR] [--file F.scala] " +
          "[--replace 'old=>new'] [--keep] [--no-color]")
        sys.exit(0)
      case bad :: _ => fail(s"unknown argument: $bad")
    }
    // `scala-cli <script> -- --file x` keeps the separator; a shebang run does not. Drop it.
    parse(args.toList.dropWhile(_ == "--"))

    if (!Files.exists(Jar))
      fail(s"server jar not found at $Jar — build it with: ./mill mcp.assembly  (or set SCALASEMANTIC_JAR)")

    file match {
      // Your own project, your own file. Read-only unless you explicitly ask for a write.
      case Some(f) =>
        val root = project.toAbsolutePath.normalize()
        if (!Files.exists(root.resolve(f))) fail(s"no such file: ${root.resolve(f)}")
        if (replace.isDefined)
          println(yellow(s"about to modify ${root.resolve(f)} for real — Ctrl-C now if that is not what you want"))
        val ok = roundtrip(root, f, replace)
        println()
        println(if (ok) green(bold("PASS — annotated in, clean on disk")) else red(bold("FAIL")))
        sys.exit(if (ok) 0 else 1)

      // Self-contained: a throwaway compiled project, so the write half is always safe to run.
      case None =>
        val sandbox = Files.createTempDirectory("scalasemantic-demo")
        val root = fixtureProject(sandbox)
        println(dim(s"sandbox: $root"))
        // A body edit, not a rename: the point here is the buffer/disk contrast, and a rename
        // would need its call sites updated too (that is what rename_plan is for).
        val default = "xs.map(s => s.length)" -> "xs.map(s => s.length * 2)"
        val ok = roundtrip(root, "Fixture.scala", replace.orElse(Some(default)))

        step(7, "And it still compiles")
        compile(root)
        println(green("  ✓ scala-cli compile succeeded on the written file"))

        println()
        println(if (ok) green(bold("PASS — annotated in, clean on disk")) else red(bold("FAIL")))
        if (keep) println(dim(s"kept: $root")) else rmTree(sandbox)
        sys.exit(if (ok) 0 else 1)
    }
  }
}
