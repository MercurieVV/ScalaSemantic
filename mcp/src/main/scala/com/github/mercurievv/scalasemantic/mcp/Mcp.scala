package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** A single MCP tool: its name, one-line description, JSON-Schema for arguments, and a handler
  * producing a (deliberately lean) JSON result.
  */
final case class Tool(
    name: String,
    description: String,
    inputSchema: ujson.Value,
    run: ujson.Value => ujson.Value
)

/** Hand-rolled MCP server over stdio JSON-RPC (no Scala MCP SDK exists). Newline-delimited JSON-RPC
  * 2.0: one message per line in, one per line out, logs to stderr.
  *
  * Token discipline: every result is minimal by default — locations collapse to `uri:line:col`
  * strings, signatures to a single rendered line, related symbols to display names. Callers opt
  * into structured detail with `"detailed": true`, and `find_usages` is paged. Empty fields are
  * dropped rather than serialized as `null`/`[]`.
  */
object Mcp:

  val ProtocolVersion = "2025-06-18"
  val ServerName = "scala-semantic-mcp"
  // dynver-derived at build time (see mcp/buildInfoKeys), so it tracks the published version.
  val ServerVersion = com.github.mercurievv.scalasemantic.buildinfo.BuildInfo.version

  /** Usage guidance returned on `initialize` (the MCP `instructions` field). Tells the model how to
    * drive the tools and — crucially — to prefer them over text search for Scala code questions.
    */
  val Instructions: String =
    """ScalaSemantic answers questions about Scala code from compiler-emitted SemanticDB: resolved
      |symbols, types, and references exactly as the compiler saw them — not text matches.
      |
      |Prefer these tools over grep/ripgrep/glob/file-reading for ANY question about Scala symbols,
      |types, references, hierarchies, implicits, or call paths — they are both more accurate AND
      |cheaper. Each returns one exact, compact answer (a symbol, a signature, a `uri:line:col` list)
      |instead of many noisy text matches you then have to read and filter, so they spend far fewer
      |tokens and less context. And text search is unreliable on Scala: it MISSES renames, re-exports,
      |type-inferred uses and implicits, and OVER-MATCHES comments, strings, and unrelated same-named
      |identifiers. Reach for text search only for non-symbol content (comments, string literals, build
      |files, non-Scala files), or when these tools genuinely do not fit.
      |
      |Pick the tool by what you want to know:
      |  who calls / references a symbol, where it is used  → find_usages
      |  subtypes / supertypes / implementers of a type     → class_hierarchy
      |  a method's signature / parameters / return         → method_signature
      |  the overloads of a method                          → find_overloads
      |  members a type declares vs. inherits               → members
      |  which givens/implicits apply, the implicit chain   → resolve_implicits, trace_implicit_chain
      |  whether method A reaches B, the call path          → call_path
      |  the symbol/type at a source position               → type_at_position
      |  the symbol for a plain name                        → find_symbol
      |
      |Symbols: every tool except find_symbol and type_at_position takes a SemanticDB symbol string
      |(grammar: package `foo/`, type `Foo#`, term `foo.`, method `foo().`, overloads `foo().(+1)`).
      |Do NOT hand-write or guess these — they are easy to get subtly wrong. Always obtain a symbol
      |from `find_symbol` (from a name) or `type_at_position` (from a source location), then pass it on.
      |
      |Worked example — "who calls Service.run?":
      |  1. find_symbol "run"  → choose the result whose symbol ends `…/Service#run().`
      |  2. find_usages (or call_path) with that symbol.
      |
      |Recovery: if a tool returns `found:false`, `count:0`, or empty lists, the symbol string is
      |almost certainly wrong — do NOT retry it verbatim and do NOT fall back to grep. Re-resolve the
      |name with find_symbol (narrow with `exact`, `kind`, or `pathFilter`) and use the corrected symbol.
      |
      |Output is lean by default (locations as `uri:line:col`, signatures one line); pass
      |`"detailed": true` to expand, and page find_usages via `limit`/`offset`.""".stripMargin

  /** Pure request handler (no I/O) so it can be unit-tested. Returns `None` for notifications. */
  def handle(req: ujson.Value, tools: List[Tool]): Option[ujson.Value] =
    val method = req.obj.get("method").map(_.str).getOrElse("")
    val idOpt = req.obj.get("id")
    method match
      case "initialize" =>
        val pv = req.obj
          .get("params")
          .flatMap(_.obj.get("protocolVersion"))
          .map(_.str)
          .getOrElse(ProtocolVersion)
        idOpt.map(id =>
          ok(
            id,
            obj(
              "protocolVersion" -> ujson.Str(pv),
              "capabilities" -> obj("tools" -> ujson.Obj()),
              "serverInfo" -> obj(
                "name" -> ujson.Str(ServerName),
                "version" -> ujson.Str(ServerVersion)
              ),
              "instructions" -> ujson.Str(Instructions)
            )
          )
        )

      case "tools/list" =>
        val list = tools.map(t =>
          obj(
            "name" -> ujson.Str(t.name),
            "description" -> ujson.Str(t.description),
            "inputSchema" -> t.inputSchema
          )
        )
        idOpt.map(id => ok(id, obj("tools" -> ujson.Arr.from(list))))

      case "tools/call" =>
        idOpt.map { id =>
          val params = req.obj.getOrElse("params", ujson.Obj())
          val name = params.obj.get("name").map(_.str).getOrElse("")
          val args = params.obj.getOrElse("arguments", ujson.Obj())
          tools.find(_.name == name) match
            case None => err(id, -32602, s"Unknown tool: $name")
            case Some(tool) =>
              scala.util.Try(tool.run(args)) match
                case scala.util.Success(res) =>
                  ok(id, obj("content" -> ujson.Arr(textBlock(ujson.write(res)))))
                case scala.util.Failure(e) =>
                  ok(
                    id,
                    obj(
                      "content" -> ujson.Arr(textBlock(s"error: ${e.getMessage}")),
                      "isError" -> ujson.Bool(true)
                    )
                  )
        }

      case "ping"                              => idOpt.map(id => ok(id, ujson.Obj()))
      case m if m.startsWith("notifications/") => None
      case _ => idOpt.map(id => err(id, -32601, s"Method not found: $method"))

  /** Map a stream of newline-delimited request lines to response lines: blanks and unparseable
    * lines are skipped, notifications produce no output. Pure, so the loop itself is testable.
    */
  def process(lines: Iterator[String], tools: List[Tool]): Iterator[String] =
    lines
      .filter(_.nonEmpty)
      .flatMap(line => scala.util.Try(ujson.read(line)).toOption.flatMap(handle(_, tools)))
      .map(ujson.write(_))

  /** Blocking stdio loop. Loads the SemanticDB index for `root` once, then serves requests.
    *
    * `classpath`, when given, enables the presentation-compiler second backend (live overlay of
    * uncompiled buffers via the tools' `source` argument). It is the target project's compile
    * classpath, supplied as either a path-separated string or a path to a file containing one
    * (newline or path-separator delimited). Falls back to the `SCALASEMANTIC_CLASSPATH` env var.
    * Absent, the server is index-only and `source` arguments are ignored.
    */
  def serve(root: String, classpath: Option[String] = None): Unit =
    val rootPath = Paths.get(root).toAbsolutePath.nn
    val backend = resolveClasspath(classpath).map { cp =>
      System.err.println(s"[$ServerName] PC backend enabled (${cp.size} classpath entries)")
      new PresentationCompilerBackend(cp, workspace = Some(rootPath))
    }
    val tools = McpTools.all(Analyzer(SemanticIndex.fromProject(root), backend), rootPath)
    System.err.println(
      s"[$ServerName] serving from '$root' with ${tools.size} tools" +
        (if backend.isEmpty then " (index-only; pass a classpath to enable live buffers)" else "")
    )
    val reader = java.io.BufferedReader(java.io.InputStreamReader(System.in, "UTF-8"))
    val out = java.io.PrintStream(System.out, true, "UTF-8")
    val lines = Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten
    process(lines, tools).foreach(out.println)

  /** Resolve the classpath spec (arg or `SCALASEMANTIC_CLASSPATH`) to a list of paths. A spec that
    * names an existing file is read as its contents (newline- or path-separator-delimited);
    * anything else is treated as a literal path-separated classpath.
    */
  private def resolveClasspath(arg: Option[String]): Option[Seq[Path]] =
    arg
      .orElse(Option(System.getenv("SCALASEMANTIC_CLASSPATH")))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { spec =>
        val raw =
          val asFile = Paths.get(spec)
          if Files.isRegularFile(asFile) then Files.readString(asFile) else spec
        raw
          .split("[\n" + java.io.File.pathSeparator + "]")
          .iterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(Paths.get(_))
          .toVector
      }
      .filter(_.nonEmpty)

  // --- JSON-RPC envelope helpers --------------------------------------------

  private def ok(id: ujson.Value, result: ujson.Value): ujson.Value =
    obj("jsonrpc" -> ujson.Str("2.0"), "id" -> id, "result" -> result)

  private def err(id: ujson.Value, code: Int, message: String): ujson.Value =
    obj(
      "jsonrpc" -> ujson.Str("2.0"),
      "id" -> id,
      "error" -> obj("code" -> ujson.Num(code), "message" -> ujson.Str(message))
    )

  private def textBlock(text: String): ujson.Value =
    obj("type" -> ujson.Str("text"), "text" -> ujson.Str(text))

  private def obj(fields: (String, ujson.Value)*): ujson.Value = ujson.Obj.from(fields)
