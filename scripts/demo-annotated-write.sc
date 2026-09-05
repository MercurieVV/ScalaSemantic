#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::upickle::4.2.1

// A LOOK-AT-IT demo — and a real check — of the annotation-aware read/write path. It prints every
// stage so you can see with your own eyes that the buffer an agent edits is annotated while the
// file that lands on disk is not, and it exits non-zero the moment that stops being true.
//
//   ./mill mcp.assembly
//   scala-cli scripts/demo-annotated-write.sc
//
// Modes:
//   (no args)                          narrated demo on a throwaway fixture, then the edge cases
//   --file Foo.scala                   annotate one of YOUR files and print it — read only
//   --file Foo.scala --replace 'a=>b'  ...and actually write the edited buffer back
//   --project DIR                      workspace root for --file (default: .)
//   --keep                             leave the sandbox on disk and print its path
//   --no-color                         plain output for piping
//   --update-golden                    print the drifted annotation text instead of failing
//
// Env: SCALASEMANTIC_JAR overrides the server jar (default out/mcp/assembly.dest/out.jar).

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object DemoAnnotatedWrite {

  // --- terminal ------------------------------------------------------------------------------

  private var color = true
  private var updateGolden = false
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
  def show(text: String, highlight: Boolean = true): Unit =
    text.linesIterator.zipWithIndex.foreach { case (line, i) =>
      val body =
        if (highlight) SemBlock.replaceAllIn(line, m => cyan(m.matched).replace("\\", "\\\\"))
        else line
      println(dim(f"${i + 1}%4d │ ") + body)
    }

  def verdict(ok: Boolean, msg: String): Boolean = {
    println(if (ok) green(s"  ✓ $msg") else red(s"  ✗ $msg"))
    ok
  }

  /** Sandbox to clean up on the way out, including the `fail` path — a leaked temp project just
    * confuses the next run.
    */
  private var sandbox = Option.empty[Path]
  private var keepSandbox = false

  def cleanup(): Unit = sandbox.foreach { dir =>
    if (keepSandbox) println(dim(s"kept: $dir")) else rmTree(dir)
  }

  def fail(msg: String): Nothing = {
    System.err.println(red(s"error: $msg"))
    cleanup()
    sys.exit(1)
  }

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
    val lines = init +: calls.zipWithIndex.map { case (c, i) =>
      ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> (i + 1), "method" -> "tools/call", "params" -> c))
    }
    val cmd =
      Seq("java", "-cp", Jar.toString, "com.github.mercurievv.scalasemantic.mcpServer", root.toString)
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(root.toFile)
    val proc = pb.start()
    proc.getOutputStream.write(lines.mkString("", "\n", "\n").getBytes("UTF-8"))
    proc.getOutputStream.close()
    val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
    if (proc.waitFor() != 0)
      fail(s"server exited non-zero\n--- stdout ---\n$out\n--- stderr ---\n$err")
    out.linesIterator.filter(_.trim.nonEmpty).map(ujson.read(_)).toSeq
  }

  def call(name: String, args: (String, ujson.Value)*): ujson.Value =
    ujson.Obj("name" -> name, "arguments" -> ujson.Obj.from(args))

  /** The MCP envelope: result.content[0].text carries the tool's own JSON — except on a refusal,
    * where it is a plain-English message. Both are interesting here, so wrap the latter rather than
    * failing on it.
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
    val resp =
      rpc(root, Seq(call("annotated_source", "uri" -> uri, "format" -> "compilable", "sentinel" -> true)))
    val p = payload(resp.last)
    if (!p.obj.get("found").forall(_.bool))
      fail(s"$uri is not in the index — compile the project first, then re-run.\n${ujson.write(p)}")
    (p("source").str, p("sha256").str)
  }

  def annotatedWrite(root: Path, uri: String, text: String, baseHash: String): ujson.Value =
    payload(
      rpc(root, Seq(call("annotated_source", "uri" -> uri, "write" -> text, "baseHash" -> baseHash))).last
    )

  def sha256(p: Path): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(Files.readAllBytes(p)).map(b => f"${b & 0xff}%02x").mkString
  }

  // --- fixtures ------------------------------------------------------------------------------

  val SemBlock = """/\*SEM:[\s\S]*?:SEM\*/""".r

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

  /** The annotated buffer [[FixtureSource]] must produce, pinned. Presence of SEM blocks is a weak
    * assertion: annotations can be present and WRONG. One real bug spliced inferred type args into
    * the middle of a string literal (`"bb[Int]"` instead of `.sum[Int]`) — plausible-looking,
    * silently false, and invisible to every check that only asks whether annotations exist. Pinning
    * the text is the only thing that catches that class. Re-pin with --update-golden after a
    * deliberate change, having READ the new text first.
    */
  val FixtureAnnotatedGolden: String =
    """|object Fixture:
       |  given byLength: Ordering[String] = Ordering.by(s => s.length) /*SEM:elaborated: Ordering.by[String, Int](s => s.length)(using Ordering[Int]):SEM*/
       |
       |  def sizes(xs: List[String]) = xs.map(s => s.length) /*SEM:type: List[Int]; xs.map[Int]:SEM*/
       |
       |  def longest(xs: List[String]) = xs.max /*SEM:type: String; elaborated: xs.max[String](using byLength):SEM*/
       |
       |  val total = sizes(List("a", "bb", "ccc")).sum /*SEM:type: Int; elaborated: sizes(List.apply[String]("a", "bb", "ccc")).sum[Int](using Numeric[IntIsIntegral]):SEM*/
       |""".stripMargin

  /** Sources adversarial for the sentinel machinery rather than for the compiler: text that looks
    * like a sentinel but is the author's own, a real comment ending the way a sentinel does, CRLF
    * endings, non-ASCII. Here the risk is not a missing annotation but a DELETED line of someone's
    * real source, so each must survive an unmodified round trip byte for byte.
    */
  val EdgeFixtures: List[(String, String)] = List(
    "EdgeSemLiteral.scala" ->
      """|object EdgeSemLiteral:
         |  val marker = "/*SEM:in-a-string:SEM*/"
         |  val n = List(1, 2).sum
         |""".stripMargin,
    "EdgeSemSuffix.scala" ->
      """|object EdgeSemSuffix:
         |  val n = List(1, 2).sum // a real comment that ends like a sentinel :SEM*/
         |""".stripMargin,
    "EdgeCrLf.scala" ->
      "object EdgeCrLf:\r\n  val n = List(1, 2).sum\r\n",
    "EdgeUnicode.scala" ->
      """|object EdgeUnicode:
         |  val label = "λ → ✓ 日本語"
         |  val n = List(1, 2).sum
         |""".stripMargin
  )

  def newSandbox(): Path = {
    val dir = Files.createTempDirectory("scalasemantic-demo")
    sandbox = Some(dir)
    dir
  }

  def fixtureProject(parent: Path): Path = {
    val dir = Files.createDirectories(parent.resolve("demo-project"))
    Files.writeString(dir.resolve("project.scala"), "//> using scala 3.8.4\n")
    Files.writeString(dir.resolve("Fixture.scala"), FixtureSource)
    EdgeFixtures.foreach { case (name, src) => Files.writeString(dir.resolve(name), src) }
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
    if (Files.exists(p))
      Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  /** Naive line diff — enough to see what changed. */
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

  // --- the narrated demo ---------------------------------------------------------------------

  /** Read, edit the annotated buffer, write it back, then prove what did and did not reach disk.
    * `replace` is the edit an agent would make, applied to the ANNOTATED text; `golden` pins the
    * annotated buffer's exact text when the caller knows what it must be.
    */
  def roundtrip(
      root: Path,
      uri: String,
      replace: Option[(String, String)],
      golden: Option[String] = None
  ): Boolean = {
    val file = root.resolve(uri)
    val rawBefore = Files.readString(file)
    val hashBefore = sha256(file)

    step(1, "The file as it sits on disk")
    show(rawBefore, highlight = false)
    println()
    println(dim("  Plain text. No types, no implicits — what a text tool would hand you."))

    step(2, "annotated_source(format=compilable, sentinel=true)  ← what the agent READS")
    val (buffer, hash) = annotatedRead(root, uri)
    show(buffer)
    println()
    println(dim(s"  sha256 = $hash"))
    println(dim("  The /*SEM:...:SEM*/ blocks are inferred types, implicit arguments and"))
    println(dim("  conversions the compiler resolved. They are comments, so this still compiles."))
    println()

    // Reading must not touch the file, and the annotations must be RIGHT, not merely present.
    val goldenOk = golden match {
      case None                         => true
      case Some(want) if buffer == want => verdict(true, "the annotations match the pinned golden text")
      case Some(want) if updateGolden =>
        println(yellow("  ! annotation golden drifted; --update-golden, so here is the actual text:"))
        println(buffer)
        true
      case Some(want) =>
        val ok = verdict(false, "the annotations match the pinned golden text")
        diff(want, buffer)
        ok
    }
    val readOk =
      verdict(sha256(file) == hashBefore, "reading left the file on disk untouched") && goldenOk

    replace match {
      case None =>
        println()
        println(yellow("  Read-only mode — pass --replace 'old=>new' to see the write half."))
        readOk

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
        // The oracle is the ORIGINAL source with the same textual edit applied — derived from the
        // input, not from a second copy of the product's own strip regex, which would agree with a
        // broken one and pass.
        val expectedDisk = Option.when(rawBefore.contains(from))(rawBefore.replace(from, to))
        val checks = Seq(
          verdict(SemBlock.findFirstIn(buffer).nonEmpty, "the buffer the agent read WAS annotated"),
          verdict(!rawAfter.contains("SEM:"), "no SEM sentinel reached disk"),
          verdict(!rawAfter.contains("⟹"), "no ⟹ annotation reached disk"),
          verdict(rawAfter.contains(to), s"the edit ('$to') did reach disk"),
          expectedDisk match {
            case Some(want) =>
              val ok =
                verdict(rawAfter == want, "disk == the original source with just that edit, byte for byte")
              if (!ok) diff(want, rawAfter)
              ok
            case None =>
              println(yellow("  ~ the edit touches annotation text only, so the independent oracle is skipped"))
              true
          }
        )

        // A stale baseHash must be refused FOR THE RIGHT REASON: any error at all satisfies a bare
        // `written != true`, including one that means the write path is simply broken.
        val stale = annotatedWrite(root, uri, edited, hash)
        val staleMsg = stale.obj.get("message").map(_.str).getOrElse(ujson.write(stale))
        val refusedForDrift =
          !stale.obj.get("written").exists(_.bool) && staleMsg.contains("changed on disk")
        println()
        println(dim("  replaying the same write with the now-stale baseHash:"))
        println(dim("  " + staleMsg))
        val staleOk =
          verdict(refusedForDrift, "a stale baseHash is refused as a concurrent edit, not some other error")
        val untouched = verdict(
          Files.readString(file) == rawAfter,
          "the refused write left the file exactly as the accepted one wrote it"
        )

        readOk && checks.forall(identity) && staleOk && untouched
    }
  }

  // --- the edge cases ------------------------------------------------------------------------

  /** Read the annotated buffer and write it straight back, unmodified: the file must come out byte
    * for byte identical. This is the property that matters for text the sentinel machinery could
    * mistake for its own, because there the failure is not a missing annotation but a DELETED piece
    * of someone's real source.
    */
  def identityRoundtrip(root: Path, uri: String): Boolean = {
    val file = root.resolve(uri)
    val before = Files.readAllBytes(file)
    val (buffer, hash) = annotatedRead(root, uri)
    val res = annotatedWrite(root, uri, buffer, hash)
    if (!res.obj.get("written").exists(_.bool))
      verdict(false, s"$uri — write refused: ${ujson.write(res)}")
    else {
      val after = Files.readAllBytes(file)
      val ok = java.util.Arrays.equals(before, after)
      val _ = verdict(ok, s"$uri — unmodified round trip is byte-identical")
      if (!ok) diff(new String(before, "UTF-8"), new String(after, "UTF-8"))
      ok
    }
  }

  def main(args: Array[String]): Unit = {
    var project = Paths.get(".")
    var file = Option.empty[String]
    var replace = Option.empty[(String, String)]

    def parse(rest: List[String]): Unit = rest match {
      case Nil                    => ()
      case "--project" :: v :: t  => project = Paths.get(v); parse(t)
      case "--file" :: v :: t     => file = Some(v); parse(t)
      case "--keep" :: t          => keepSandbox = true; parse(t)
      case "--no-color" :: t      => color = false; parse(t)
      case "--update-golden" :: t => updateGolden = true; parse(t)
      case "--replace" :: v :: t =>
        v.split("=>", 2) match {
          case Array(from, to) => replace = Some(from.trim -> to.trim)
          case _               => fail(s"--replace expects 'old=>new', got '$v'")
        }
        parse(t)
      case ("--help" | "-h") :: _ =>
        println(
          "usage: demo-annotated-write.sc [--project DIR] [--file F.scala] " +
            "[--replace 'old=>new'] [--keep] [--no-color] [--update-golden]"
        )
        sys.exit(0)
      case bad :: _ => fail(s"unknown argument: $bad")
    }
    // `scala-cli <script> -- --file x` keeps the separator; a shebang run does not. Drop it.
    parse(args.toList.dropWhile(_ == "--"))

    if (!Files.exists(Jar))
      fail(s"server jar not found at $Jar — build it with: ./mill mcp.assembly  (or set SCALASEMANTIC_JAR)")

    val ok = file match {
      // Your own project, your own file. Read-only unless you explicitly ask for a write.
      case Some(f) =>
        val root = project.toAbsolutePath.normalize()
        if (!Files.exists(root.resolve(f))) fail(s"no such file: ${root.resolve(f)}")
        if (replace.isDefined)
          println(
            yellow(s"about to modify ${root.resolve(f)} for real — Ctrl-C now if that is not what you want")
          )
        roundtrip(root, f, replace)

      // Self-contained: a throwaway compiled project, so the write half is always safe to run.
      case None =>
        val root = fixtureProject(newSandbox())
        println(dim(s"sandbox: $root"))
        // A body edit, not a rename: the point here is the buffer/disk contrast, and a rename would
        // need its call sites updated too (that is what rename_plan is for).
        val default = "xs.map(s => s.length)" -> "xs.map(s => s.length * 2)"
        val demo =
          roundtrip(root, "Fixture.scala", replace.orElse(Some(default)), Some(FixtureAnnotatedGolden))

        step(7, "And it still compiles")
        compile(root)
        println(green("  ✓ scala-cli compile succeeded on the written file"))

        step(8, "Text the sentinel machinery could mistake for its own")
        val edges = EdgeFixtures.map { case (name, _) => identityRoundtrip(root, name) }
        demo && edges.forall(identity)
    }

    println()
    println(if (ok) green(bold("PASS — annotated in, clean on disk")) else red(bold("FAIL")))
    cleanup()
    sys.exit(if (ok) 0 else 1)
  }
}
