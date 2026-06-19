package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

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
  val ServerVersion = "0.1.0"

  /** Usage guidance returned on `initialize` (the MCP `instructions` field). Tells the model how to
    * drive the tools and — crucially — to prefer them over text search for Scala code questions.
    */
  val Instructions: String =
    """ScalaSemantic answers questions about Scala code from compiler-emitted SemanticDB, so results
      |reflect what the compiler actually resolved — not text matches.
      |
      |PREFER THESE TOOLS OVER grep / text search whenever examining Scala code: finding usages,
      |subtypes, method signatures, implicits/givens, or call paths. grep matches characters and
      |misses/over-matches; these tools match exact symbols. Only fall back to text search when no
      |tool fits (e.g. comments, strings, non-Scala files).
      |
      |Every tool takes a SemanticDB symbol string (grammar: package `foo/`, type `Foo#`, term
      |`foo.`, method `foo().` with `(+1)` overload disambiguators). To get one from a plain name,
      |call `find_symbol` FIRST, then feed the returned `symbol` into the other tools. `type_at_position`
      |also yields a symbol from a source position.
      |
      |Results are lean by default (locations as `uri:line:col`, signatures one line); pass
      |`"detailed": true` to expand, and `find_usages` is paged via `limit`/`offset`.""".stripMargin

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

  /** Blocking stdio loop. Loads the SemanticDB index for `root` once, then serves requests. */
  def serve(root: String): Unit =
    val tools = McpTools.all(Analyzer(SemanticIndex.fromProject(root)))
    System.err.println(s"[$ServerName] serving from '$root' with ${tools.size} tools")
    val reader = java.io.BufferedReader(java.io.InputStreamReader(System.in, "UTF-8"))
    val out = java.io.PrintStream(System.out, true, "UTF-8")
    val lines = Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten
    process(lines, tools).foreach(out.println)

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
