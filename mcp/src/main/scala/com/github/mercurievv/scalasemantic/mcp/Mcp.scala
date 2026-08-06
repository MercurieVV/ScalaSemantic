package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

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
  * 2.0: one message per line in, one per line out, logs to a file.
  *
  * Token discipline: every result is minimal by default — locations collapse to `uri:line:col`
  * strings, signatures to a single rendered line, related symbols to display names. Callers opt
  * into structured detail with `"detailed": true`, and `find_usages` is paged. Empty fields are
  * dropped rather than serialized as `null`/`[]`.
  */
object Mcp:

  private[mcp] final case class McpState(
      root: Path,
      analyzer: Analyzer,
      tools: List[Tool],
      pcBackends: Option[ModulePresentationCompilerBackends] = None,
      classpathSource: Option[String] = None,
      fingerprint: SemanticIndex.Fingerprint = SemanticIndex.Fingerprint(0, 0L),
      coverage: SemanticIndex.Coverage = SemanticIndex.Coverage.empty
  ):
    def pcSelector: Option[String => Option[PresentationCompilerBackend]] =
      pcBackends.map(_.backendFor)

  private[mcp] val state = new AtomicReference[Option[McpState]](None)
  private[mcp] val stateCache = new java.util.concurrent.ConcurrentHashMap[Path, McpState]()
  private[mcp] val backendFor =
    new AtomicReference[Option[String => Option[PresentationCompilerBackend]]](None)
  private[mcp] val log = new AtomicReference[String => Unit](_ => ())
  private[mcp] val stateFactory = new AtomicReference[Path => McpState](root =>
    val index = SemanticIndex.fromProject(root.toString)
    val az = Analyzer(index, pcSelector = None)
    val cov = SemanticIndex.coverage(Seq(root), index)
    new McpState(
      root,
      az,
      toolsFor(az, root, cov),
      fingerprint = SemanticIndex.fingerprint(Seq(root)),
      coverage = cov
    )
  )

  private[mcp] def currentState: Option[McpState] = state.get()

  private[mcp] def currentRoot: Path =
    currentState.map(_.root).getOrElse(Paths.get(".").toAbsolutePath.normalize().nn)

  private[mcp] def activeTools(fallback: List[Tool]): List[Tool] =
    currentState.map(_.tools).getOrElse(fallback)

  private[mcp] def activateState(next: McpState): Unit =
    state.set(Some(next))
    backendFor.set(next.pcSelector)

  /** Drop any cached state for `root` and build a fresh one, activating it.
    *
    * Closing the dropped state's presentation-compiler backends is part of the contract: `serve`'s
    * `finally` only closes what is still in `stateCache`, so a replaced entry would otherwise leak
    * its backends for the lifetime of the process.
    */
  private[mcp] def rebuildState(root: Path, reason: String, log: String => Unit): McpState =
    Option(stateCache.remove(root)).foreach(old =>
      scala.util
        .Try(old.pcBackends.foreach(_.close()))
        .failed
        .foreach(t => log(s"Failed to close presentation-compiler backends for $root: $t"))
    )
    log(s"Rebuilding Analyzer for workspace root: $root ($reason)")
    val next = stateFactory.get()(root)
    val _ = stateCache.put(root, next)
    activateState(next)
    next

  /** Re-check the active root's `*.semanticdb` fingerprint against disk and rebuild if it moved.
    *
    * Runs on every answering request. `SemanticIndex.fingerprint` walks the tree without parsing
    * anything, and correctness is the point: before this, a recompile left every tool answering
    * from the previous index indefinitely, with no signal that it was doing so (#290). A failure to
    * walk degrades to serving the existing state — never to a failed request.
    */
  private[mcp] def ensureFresh(): Unit =
    val _ = scala.util.Try {
      currentState.foreach { st =>
        val diskFingerprint = SemanticIndex.fingerprint(Seq(st.root))
        if diskFingerprint != st.fingerprint then
          val _ = rebuildState(st.root, "semanticdb files changed on disk", log.get())
      }
    }

  private[mcp] def toolsFor(
      az: Analyzer,
      root: Path,
      coverage: SemanticIndex.Coverage = SemanticIndex.Coverage.empty
  ): List[Tool] =
    McpTools.all(az, root, coverage) ++ List(
      setWorkspaceRootTool(log.get()),
      getWorkspaceRootTool,
      refreshWorkspaceTool(log.get())
    )

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
      |search_text — scoped text/regex search over .scala files (string literals/comments; not
      |symbol-aware — use find_symbol/find_usages for identifiers) — is the sanctioned in-MCP
      |replacement for that grep escape hatch.
      |
      |Pick the tool by what you want to know:
      |  who calls / references a symbol, where it is used    → find_usages (optional
      |    contextLines param returns surrounding source lines; for a case class it also
      |    returns construction/copy/accessor sites under `related`)
      |  subtypes / supertypes / implementers of a type       → class_hierarchy
      |  a method's signature / parameters / return           → method_signature
      |  the overloads of a method                            → find_overloads
      |  members a type declares vs. inherits                 → members
      |  which givens/implicits apply, the implicit chain     → resolve_implicits, trace_implicit_chain
      |  whether method A reaches B, the call path            → call_path
      |  the symbol/type at a source position                 → type_at_position
      |  the symbol for a plain name                          → find_symbol
      |  what's important / where to start, dep cycles        → structure
      |  a file's structure / where to edit (don't read it)   → document_outline (narrow a
      |    big file with query/symbol/kind/maxDepth; pass source for an uncompiled buffer)
      |  the full text of a .scala file (read it THIS way)    → annotated_source
        |  the exact edits to rename a symbol safely            → rename_plan
        |  rename multiple symbols in one request, reporting edit-range
        |    conflicts instead of silently merging them → batch_rename_plan
        |  the edits to move a symbol to another package        → move_plan
        |  the edits to extract a code range into a new method  → extract_method_plan
        |  where a val/binding flows across method boundaries   → value_flow
        |  current stateful workspace root                      → get_workspace_root, set_workspace_root
        |  force a rebuild the on-disk staleness check missed,
        |    or rebuild a root other than the active one        → refresh_workspace
        |
        |Freshness is automatic: every tool call re-checks the project's *.semanticdb files on disk
        |and rebuilds the index when they changed, so a recompile needs no refresh_workspace call.
        |
        |Coverage: get_workspace_root / set_workspace_root report `coverage` (`sources` vs
        |`indexed`). An empty result returned while coverage is partial carries a `coverageHint` —
        |read it as "may not be indexed", NOT as "does not exist", and check that the build compiles
        |the scope in question (for scala-cli, test sources need `--test`).
        |
        |After changing working directories (worktree switch, cd, subproject entry, or subagent cwd
        |change), call set_workspace_root with the new absolute path before any other ScalaSemantic tool.
        |If unsure, call get_workspace_root first. This is a discipline rule; current MCP clients do not
        |reliably reconnect stdio servers or notify roots for cwd changes.
        |
        |Symbols: every tool except find_symbol and type_at_position takes a SemanticDB symbol string
      |(grammar: package `foo/`, type `Foo#`, term `foo.`, method `foo().`, overloads `foo().(+1)`).
      |Do NOT hand-write or guess these — they are easy to get subtly wrong. Always obtain a symbol
      |from `find_symbol` (from a name) or `type_at_position` (from a source location), then pass it on.
      |
      |Worked example — "who calls Service.run?":
      |  1. find_symbol "run"                                 → choose the result whose symbol ends `…/Service#run().`
      |  2. find_usages (or call_path) with that symbol.
      |
      |Recovery: if a tool returns `found:false`, `count:0`, or empty lists, the symbol string is
      |almost certainly wrong — do NOT retry it verbatim and do NOT fall back to grep. Re-resolve the
      |name with find_symbol (narrow with `exact`, `kind`, or `pathFilter`) and use the corrected symbol.
      |If EVERY tool comes back empty even after re-resolving, the project may not have SemanticDB
      |enabled or compiled yet — see the Integration doc's "Prerequisite" section for your build tool.
      |
      |Output is lean by default (locations as `uri:line:col`, signatures one line); pass
      |`"detailed": true` to expand, and page find_usages via `limit`/`offset`.""".stripMargin

  /** Pure request handler (no I/O by default) so it can be unit-tested. `onToolCall(name, args)` is
    * invoked just before a tool runs — `serve` passes a file debug logger; tests use the no-op
    * default. Returns `None` for notifications.
    */
  def handle(
      req: ujson.Value,
      tools: List[Tool],
      onToolCall: (String, ujson.Value) => Unit = (_, _) => ()
  ): Option[ujson.Value] =
    val method = req.obj.get("method").map(_.str).getOrElse("")
    val idOpt = req.obj.get("id")
    // Every answering request re-checks the index against disk first: a stale answer is
    // indistinguishable from a correct one, so the check cannot be left to the caller (#290).
    if method == "tools/call" || method == "tools/list" then ensureFresh()
    val currentTools = activeTools(tools)

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
        val list = currentTools.map(t =>
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
          currentTools.find(_.name == name) match
            case None       => err(id, -32602, s"Unknown tool: $name")
            case Some(tool) =>
              onToolCall(name, args)
              runToolGuarded(id, name, tool, args)
        }

      case "ping"                              => idOpt.map(id => ok(id, ujson.Obj()))
      case m if m.startsWith("notifications/") => None
      case _ => idOpt.map(id => err(id, -32601, s"Method not found: $method"))

  /** Map a stream of newline-delimited request lines to response lines: blanks and unparseable
    * lines are skipped, notifications produce no output. `onToolCall` is forwarded to [[handle]]
    * (no-op by default). Pure, so the loop itself is testable.
    */
  def process(
      lines: Iterator[String],
      tools: List[Tool],
      onToolCall: (String, ujson.Value) => Unit = (_, _) => ()
  ): Iterator[String] =
    lines
      .filter(_.nonEmpty)
      .flatMap(line =>
        // Guarded twice over: `handle` already converts a failing tool into an isError response,
        // and this catches anything thrown outside it (parsing, dispatch, serialization) so one bad
        // line ends a request rather than the iterator — and with it the whole stdio loop (#292).
        guarded("request", log.get())(
          scala.util.Try(ujson.read(line)).toOption.flatMap(handle(_, tools, onToolCall))
        ).toOption.flatten
      )
      .map(ujson.write(_))

  /** Run `body`, converting ANY throwable — including fatal ones — into `None` plus a logged stack
    * trace.
    *
    * A `StackOverflowError` from a deep graph walk or an `OutOfMemoryError` on a large index is
    * fatal, so `scala.util.Try` (which catches `NonFatal` only) lets it escape, unwind the stdio
    * loop and kill the process; the client sees the transport close mid-call with nothing to
    * diagnose. A server must not die because one request was too expensive.
    */
  private[mcp] def guarded[A](what: String, log: String => Unit)(body: => A): Either[Throwable, A] =
    try Right(body)
    catch
      case t: Throwable =>
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        log(s"$what failed with ${t.getClass.getName}: ${t.getMessage}\n$sw")
        Left(t)

  private def runToolGuarded(
      id: ujson.Value,
      name: String,
      tool: Tool,
      args: ujson.Value
  ): ujson.Value =
    guarded(s"tool $name", log.get())(
      ok(id, obj("content" -> ujson.Arr(textBlock(ujson.write(tool.run(args))))))
    ) match
      case Right(res) => res
      case Left(t)    =>
        val detail = Option(t.getMessage).getOrElse(t.getClass.getName)
        ok(
          id,
          obj(
            "content" -> ujson.Arr(textBlock(s"error: $detail")),
            "isError" -> ujson.Bool(true)
          )
        )

  /** Open the append-mode log sink for a run. Resolves the file from `SCALASEMANTIC_LOG_FILE`, else
    * `<root>/scalasemantic-mcp.log`. Returns a `log(line)` function that prepends an ISO-8601
    * timestamp and flushes each line (so a `tail -f` sees entries live). Parent dirs are created.
    */
  def fileLogger(rootPath: Path): String => Unit =
    val logPath = Option(System.getenv("SCALASEMANTIC_LOG_FILE"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(rootPath.resolve(s"$ServerName.log"))
    Option(logPath.getParent).foreach(p => Files.createDirectories(p))
    val out = java.io.PrintWriter(
      java.io.OutputStreamWriter(
        java.io.FileOutputStream(logPath.toFile, /* append = */ true),
        "UTF-8"
      ),
      /* autoFlush = */ true
    )
    line => out.println(s"[$ServerName] ${java.time.LocalDateTime.now()} $line")

  /** Per-tool-call logger over a `log` sink: one line per call with the tool name and its arguments
    * (long arguments are truncated).
    */
  def logToolCall(log: String => Unit)(name: String, args: ujson.Value): Unit =
    val argStr = ujson.write(args)
    val shown = if argStr.length > 300 then argStr.take(300) + "…" else argStr
    log(s"call $name $shown")

  /** Logging configuration, off by default. When `enabled` is false NO log file is created — the
    * server is silent. `enabled` turns on diagnostic logging (startup line + per-tool-call lines);
    * `logOutputs` additionally records each JSON-RPC response sent back to the client (i.e. what
    * the LLM receives). `logOutputs` implies a sink, so it works even without `enabled`.
    */
  final case class LogConfig(enabled: Boolean = false, logOutputs: Boolean = false):
    /** A sink is needed if either kind of logging is on. */
    def active: Boolean = enabled || logOutputs
  object LogConfig:
    val off: LogConfig = LogConfig()

  final case class ResolvedClasspath(modules: Vector[ResolvedClasspathModule]):
    lazy val merged: Seq[Path] =
      modules.iterator.flatMap(_.classpath).toVector.distinct

    def moduleFor(uri: String, rootPath: Path): Option[ResolvedClasspathModule] =
      val sourcePath = pathForUri(uri, rootPath)
      val matched = modules
        .filter(module => sourcePath.startsWith(module.baseDir))
        .sortBy(module => -module.baseDir.getNameCount)
        .headOption
      matched.orElse:
        Option.when(merged.nonEmpty):
          ResolvedClasspathModule(
            id = "merged",
            baseDir = rootPath,
            scalaVersion = None,
            configuration = None,
            classpath = merged
          )

    def classpathFor(uri: String, rootPath: Path): Seq[Path] =
      moduleFor(uri, rootPath).map(_.classpath).getOrElse(Nil)

  final case class ResolvedClasspathModule(
      id: String,
      baseDir: Path,
      scalaVersion: Option[String],
      configuration: Option[String],
      classpath: Seq[Path]
  )

  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private[mcp] final class ModulePresentationCompilerBackends(
      classpath: ResolvedClasspath,
      rootPath: Path,
      log: String => Unit = _ => ()
  ) extends AutoCloseable:

    private val backends = ConcurrentHashMap[String, PresentationCompilerBackend]()

    def backendFor(docUri: String): Option[PresentationCompilerBackend] =
      classpath.moduleFor(docUri, rootPath).map { module =>
        backends.computeIfAbsent(
          module.id,
          _ =>
            val targetId = s"scala-semantic-pc-${module.id.replaceAll("[^A-Za-z0-9_.-]", "_")}"
            log(
              s"PC backend opened for module '${module.id}' (${module.classpath.size} classpath entries)"
            )
            new PresentationCompilerBackend(
              module.classpath,
              workspace = Some(rootPath),
              buildTargetId = targetId
            )
        )
      }

    def close(): Unit =
      backends.values().asScala.foreach(_.close())

  /** Blocking stdio loop. Loads the SemanticDB index for `root` once, then serves requests.
    *
    * `classpath`, when given, enables the presentation-compiler second backend (live overlay of
    * uncompiled buffers via the tools' `source` argument). It is the target project's compile
    * classpath, supplied as either a path-separated string, a path to a flat classpath file, or a
    * module-aware `.scala-semantic/classpath-<tool>.json` metadata file. Falls back to the
    * `SCALASEMANTIC_CLASSPATH` env var. Absent, the server is index-only and `source` arguments are
    * ignored.
    *
    * `logging` controls the (opt-in) file log; see [[LogConfig]]. Default: no log file at all.
    */
  // Throw: the loop's last-resort handler logs why the server died and then rethrows, so the
  // process still exits non-zero instead of looking like a clean shutdown.
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def serve(
      root: String,
      classpath: Option[String] = None,
      logging: LogConfig = LogConfig.off
  ): Unit =
    val rootPath = Paths.get(root).toAbsolutePath.nn
    val currentLog: String => Unit = if logging.active then fileLogger(rootPath) else (_ => ())
    Mcp.log.set(currentLog)
    // Acquire the (optional) PC backend through the #140 bracket helper so the compiler instance
    // is always shut down when the server exits — normally, on EOF, or on an unhandled exception —
    // without a hand-rolled try/finally here.
    stateCache.clear()
    Mcp.stateFactory.set(path => buildState(path, classpath, currentLog))
    val initialState = stateFactory.get()(rootPath)
    activateState(initialState)
    Mcp.stateCache.put(rootPath, initialState)
    try
      runLoop(root, rootPath, initialState.pcSelector, currentLog, logging)
      currentLog("stdin closed; shutting down")
    catch
      case t: Throwable =>
        // Nothing above should throw any more (see `guarded`); if it does, say why in the log
        // instead of letting the client observe only a closed transport (#292).
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        currentLog(s"server loop terminated by ${t.getClass.getName}: ${t.getMessage}\n$sw")
        // Rethrow so the process still exits non-zero rather than looking like a clean shutdown.
        throw t // scalafix:ok DisableSyntax.throw
    finally stateCache.values().asScala.foreach(_.pcBackends.foreach(_.close()))

  private[mcp] def buildState(
      rootPath: Path,
      classpath: Option[String],
      log: String => Unit
  ): McpState =
    resolveClasspathWithSource(classpath, rootPath) match
      case Some(resolved) =>
        val cp = resolved.classpath
        val backends = ModulePresentationCompilerBackends(cp, rootPath, log)
        log(
          s"PC backend enabled from ${resolved.source} (${cp.modules.size} modules, ${cp.merged.size} merged classpath entries)"
        )
        val selector = Some(backends.backendFor)
        val index = SemanticIndex.fromProject(rootPath.toString)
        val az = Analyzer(index, pcSelector = selector)
        val fp = SemanticIndex.fingerprint(Seq(rootPath))
        val cov = SemanticIndex.coverage(Seq(rootPath), index)
        new McpState(
          rootPath,
          az,
          toolsFor(az, rootPath, cov),
          Some(backends),
          Some(resolved.source),
          fp,
          cov
        )
      case None =>
        log(s"PC backend disabled for $rootPath; no classpath metadata found")
        val index = SemanticIndex.fromProject(rootPath.toString)
        val az = Analyzer(index, pcSelector = None)
        val fp = SemanticIndex.fingerprint(Seq(rootPath))
        val cov = SemanticIndex.coverage(Seq(rootPath), index)
        new McpState(
          rootPath,
          az,
          toolsFor(az, rootPath, cov),
          fingerprint = fp,
          coverage = cov
        )

  /** The read/eval/write loop, parameterized over the (already-acquired, optional) PC backend.
    * Split out of [[serve]] so the backend's acquire/release stays bracketed by
    * [[PresentationCompilerBackend.use]] in the caller rather than manually closed here.
    */
  private def runLoop(
      root: String,
      rootPath: Path,
      backendFor: Option[String => Option[PresentationCompilerBackend]],
      log: String => Unit,
      logging: LogConfig
  ): Unit =
    val tools = currentState
      .map(_.tools)
      .getOrElse(
        toolsFor(Analyzer(SemanticIndex.fromProject(root), pcSelector = backendFor), rootPath)
      )
    log(
      s"serving from '$root' with ${tools.size} tools" +
        (if backendFor.isEmpty then " (index-only; pass a classpath to enable live buffers)"
         else "")
    )
    val reader = java.io.BufferedReader(java.io.InputStreamReader(System.in, "UTF-8"))
    val out = java.io.PrintStream(System.out, true, "UTF-8")
    val lines = Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten
    // logToolCall logs inputs (when `enabled`); the tap below logs outputs (when `logOutputs`).
    val onCall = if logging.enabled then logToolCall(log) else (_: String, _: ujson.Value) => ()
    process(lines, tools, onCall).foreach { line =>
      if logging.logOutputs then log(s"out $line")
      out.println(line)
    }

  private[mcp] final case class ResolvedClasspathWithSource(
      classpath: ResolvedClasspath,
      source: String
  )

  /** Resolve the classpath spec (arg or `SCALASEMANTIC_CLASSPATH`) to classpath metadata. If
    * neither is supplied, discover project-local `.scala-semantic/classpath-*.json` files from the
    * active root and visible subdirectories, including build output directories. A spec that names
    * an existing JSON file is parsed as module-aware metadata. A spec that names any other existing
    * file is read as a flat classpath file (newline- or path-separator-delimited). Anything else is
    * treated as a literal path-separated classpath.
    */
  private[mcp] def resolveClasspath(
      arg: Option[String],
      rootPath: Path
  ): Option[ResolvedClasspath] =
    resolveClasspathWithSource(arg, rootPath).map(_.classpath)

  private[mcp] def resolveClasspathWithSource(
      arg: Option[String],
      rootPath: Path
  ): Option[ResolvedClasspathWithSource] =
    arg
      .orElse(Option(System.getenv("SCALASEMANTIC_CLASSPATH")))
      .map(_.trim)
      .filter(_.nonEmpty) match
      case Some(spec) =>
        val cp = resolveClasspathSpec(spec, rootPath)
        Option.when(cp.merged.nonEmpty)(ResolvedClasspathWithSource(cp, spec))
      case None =>
        val files = discoverClasspathMetadata(rootPath)
        resolveClasspathMetadataFiles(files, rootPath)
          .filter(_.merged.nonEmpty)
          .map(cp => ResolvedClasspathWithSource(cp, files.map(_.toString).mkString(", ")))

  private[mcp] def resolveClasspathSpec(spec: String, rootPath: Path): ResolvedClasspath =
    val fileRef = !spec.contains(java.io.File.pathSeparator)
    val asFile = if fileRef then resolvePath(spec, rootPath) else Paths.get(spec)
    val missingFileRef = fileRef && !Files.exists(asFile)
    if missingFileRef then ResolvedClasspath(Vector.empty)
    else if fileRef && Files.isRegularFile(asFile) && spec.endsWith(".json") then
      resolveClasspathMetadata(asFile, rootPath).getOrElse(ResolvedClasspath(Vector.empty))
    else
      val raw = if fileRef && Files.isRegularFile(asFile) then Files.readString(asFile) else spec
      ResolvedClasspath(
        Vector(
          ResolvedClasspathModule(
            id = "flat",
            baseDir = rootPath,
            scalaVersion = None,
            configuration = None,
            classpath = splitClasspath(raw).map(resolvePath(_, rootPath))
          )
        )
      )

  private[mcp] def discoverClasspathMetadata(rootPath: Path): Vector[Path] =
    val root = rootPath.toAbsolutePath.normalize().nn
    val direct = classpathMetadataFilesIn(root)
    val structured = discoverClasspathMetadataFromModules(root)
    val discovered = (direct ++ structured).distinct
    if discovered.nonEmpty then discovered else scanVisibleClasspathMetadata(root)

  private def discoverClasspathMetadataFromModules(root: Path): Vector[Path] =
    val maxDepth = 16
    val maxDirs = 5000
    def loop(
        queue: Vector[(Path, Int)],
        seenDirs: Set[Path],
        seenFiles: Set[Path],
        visited: Int,
        found: Vector[Path]
    ): Vector[Path] =
      queue.headOption match
        case None                               => found
        case Some((_, _)) if visited >= maxDirs => found
        case Some((dir, depth))                 =>
          val classpathFiles = classpathMetadataFilesIn(dir)
          val nextFiles = moduleMetadataFilesIn(dir).filterNot(seenFiles.contains)
          val childDirs =
            if depth >= maxDepth then Vector.empty
            else nextFiles.flatMap(moduleMetadataDirs(_, root))
          val nextDirs = childDirs
            .map(_.toAbsolutePath.normalize().nn)
            .filter(Files.isDirectory(_))
            .filterNot(seenDirs.contains)
          loop(
            queue.drop(1) ++ nextDirs.map(_ -> (depth + 1)),
            seenDirs ++ nextDirs,
            seenFiles ++ nextFiles,
            visited + 1,
            found ++ classpathFiles
          )

    loop(Vector(root -> 0), Set(root), Set.empty, 0, Vector.empty).distinct

  private def scanVisibleClasspathMetadata(root: Path): Vector[Path] =
    val maxDepth = 8
    val maxDirs = 2000
    def loop(
        queue: Vector[(Path, Int)],
        seen: Set[Path],
        visited: Int,
        found: Vector[Path]
    ): Vector[Path] =
      queue.headOption match
        case None                               => found.distinct
        case Some((_, _)) if visited >= maxDirs => found.distinct
        case Some((dir, depth))                 =>
          val nextFound =
            if depth > 0 then found ++ classpathMetadataFilesIn(dir)
            else found
          val children =
            if depth >= maxDepth then Vector.empty
            else
              scala.util
                .Try {
                  scala.util.Using.resource(Files.list(dir)) { stream =>
                    stream.iterator().asScala.toVector
                  }
                }
                .getOrElse(Vector.empty)
          val nextChildren = children
            .filter(p => Files.isDirectory(p) && shouldSearchDirectory(p))
            .map(_.toAbsolutePath.normalize().nn)
            .filterNot(seen.contains)
          loop(
            queue.drop(1) ++ nextChildren.map(_ -> (depth + 1)),
            seen ++ nextChildren,
            visited + 1,
            nextFound
          )

    loop(Vector(root -> 0), Set(root), 0, Vector.empty)

  private def classpathMetadataFilesIn(dir: Path): Vector[Path] =
    val metadataDir = dir.resolve(".scala-semantic").nn
    if !Files.isDirectory(metadataDir) then Vector.empty
    else
      val preferred = Vector(
        "classpath-sbt.json",
        "classpath-mill.json",
        "classpath-scala-cli.json",
        "classpath.json"
      ).map(metadataDir.resolve(_).nn).filter(p => Files.isRegularFile(p))
      val millFragments =
        scala.util
          .Try {
            scala.util.Using.resource(Files.list(metadataDir)) { stream =>
              stream.iterator().asScala.toVector.filter { p =>
                Files.isRegularFile(p) &&
                p.getFileName.toString.startsWith("classpath-mill-") &&
                p.getFileName.toString.endsWith(".json")
              }
            }
          }
          .getOrElse(Vector.empty)
      (preferred ++ millFragments).distinct

  private def moduleMetadataFilesIn(dir: Path): Vector[Path] =
    val metadataDir = dir.resolve(".scala-semantic").nn
    if !Files.isDirectory(metadataDir) then Vector.empty
    else
      Vector(
        "modules-sbt.json",
        "modules-mill.json",
        "modules-scala-cli.json",
        "modules.json"
      ).map(metadataDir.resolve(_).nn).filter(p => Files.isRegularFile(p)).distinct

  private def moduleMetadataDirs(path: Path, rootPath: Path): Vector[Path] =
    scala.util
      .Try {
        val json = ujson.read(Files.readString(path))
        json("modules").arr.toVector.flatMap { module =>
          val obj = module.obj
          Vector(
            obj.get("path_from_root").orElse(obj.get("pathFromRoot")).map(_.str),
            obj.get("path_to_out_dir").orElse(obj.get("pathToOutDir")).map(_.str)
          ).flatten.map(resolvePath(_, rootPath))
        }
      }
      .getOrElse(Vector.empty)

  private def shouldSearchDirectory(path: Path): Boolean =
    val name = path.getFileName.toString
    !name.startsWith(".") &&
    !Set("node_modules", "project").contains(name)

  private def resolveClasspathMetadata(path: Path, rootPath: Path): Option[ResolvedClasspath] =
    resolveClasspathMetadataFiles(metadataFilesFor(path), rootPath)

  private def metadataFilesFor(path: Path): Vector[Path] =
    if path.getFileName.toString == "classpath-mill.json" then
      val parent = path.getParent
      if Option(parent).isDefined && Files.exists(parent) then
        scala.util.Using.resource(Files.list(parent)) { stream =>
          stream
            .iterator()
            .asScala
            .filter(p =>
              Files.isRegularFile(p) && p.getFileName.toString
                .startsWith("classpath-mill-") && p.getFileName.toString.endsWith(".json")
            )
            .toVector
        } :+ path
      else Vector(path)
    else Vector(path)

  private def resolveClasspathMetadataFiles(
      paths: Vector[Path],
      rootPath: Path
  ): Option[ResolvedClasspath] =
    scala.util.Try {
      val files = paths.flatMap(metadataFilesFor).distinct
      val modules = files.distinct.filter(Files.exists(_)).flatMap { f =>
        val json = ujson.read(Files.readString(f))
        json("modules").arr.toVector.flatMap { module =>
          val classpath = module("classpath").arr.toVector
            .map(entry => resolvePath(entry.str, rootPath))
            .distinct
          if classpath.isEmpty then None
          else
            val id = module.obj.get("id").map(_.str).getOrElse("")
            val baseDir = module.obj.get("baseDir").map(_.str).getOrElse(".")
            Some(
              ResolvedClasspathModule(
                id = id,
                baseDir = resolvePath(baseDir, rootPath),
                scalaVersion = module.obj.get("scalaVersion").map(_.str),
                configuration = module.obj.get("configuration").map(_.str),
                classpath = classpath
              )
            )
        }
      }
      ResolvedClasspath(modules)
    }.toOption

  private def splitClasspath(raw: String): Vector[String] =
    raw
      .split("[\\n" + java.io.File.pathSeparator + "]")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector

  private def resolvePath(path: String, rootPath: Path): Path =
    val p = Paths.get(path)
    if p.isAbsolute then p.normalize().nn else rootPath.resolve(p).normalize().nn

  private def pathForUri(uri: String, rootPath: Path): Path =
    val raw =
      scala.util
        .Try(java.net.URI.create(uri))
        .toOption
        .filter(u => Option(u.getScheme).isDefined) match
        case Some(parsed) if parsed.getScheme == "file" => Paths.get(parsed)
        case _                                          => Paths.get(uri)
    if raw.isAbsolute then raw.normalize().nn else rootPath.resolve(raw).normalize().nn

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

  private[mcp] def setWorkspaceRootTool(
      log: String => Unit
  ): Tool =
    McpToolsSupport.tool(
      "set_workspace_root",
      "Update the stateful current workspace root for semantic analysis. Relocates the indexed root dynamically.",
      List(("path", "string", "absolute or relative path to the new workspace root")),
      List("path")
    ) { args =>
      val rawPath = McpToolsSupport.argStr(args, "path")
      if (rawPath.trim.isEmpty) {
        sys.error("path parameter cannot be empty")
      }
      val targetPath = Paths.get(rawPath)
      val resolvedPath =
        (if (targetPath.isAbsolute) targetPath else currentRoot.resolve(targetPath)).normalize().nn

      if (!Files.exists(resolvedPath)) {
        sys.error(s"Path does not exist: $resolvedPath")
      }
      if (!Files.isDirectory(resolvedPath)) {
        sys.error(s"Path is not a directory: $resolvedPath")
      }

      val diskFingerprint = SemanticIndex.fingerprint(Seq(resolvedPath))
      val stale = Option(stateCache.get(resolvedPath)).exists(_.fingerprint != diskFingerprint)
      if (stale) {
        log(
          s"Stale index detected for $resolvedPath (semanticdb files changed on disk); rebuilding"
        )
        stateCache.remove(resolvedPath)
      }
      val isCached = !stale && stateCache.containsKey(resolvedPath)
      val newState = stateCache.computeIfAbsent(
        resolvedPath,
        r => {
          log(s"Initializing Analyzer for new workspace root: $r")
          stateFactory.get()(r)
        }
      )

      activateState(newState)

      ujson.Obj(
        "root" -> ujson.Str(resolvedPath.toString),
        "cached" -> ujson.Bool(isCached),
        "classpath" -> newState.classpathSource.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "semanticdbFileCount" -> ujson.Num(newState.fingerprint.fileCount),
        "semanticdbNewestMtime" -> ujson.Str(
          java.time.Instant.ofEpochMilli(newState.fingerprint.newestMtimeMillis).toString
        ),
        "coverage" -> coverageJson(newState.coverage)
      )
    }

  /** How much of the project's Scala source the loaded index covers. Informational on its own —
    * standalone scripts and files outside the build are legitimately unindexed — but it is what
    * turns an empty result into "possibly a coverage gap" rather than "does not exist" (#291).
    */
  private[mcp] def coverageJson(c: SemanticIndex.Coverage): ujson.Value =
    obj(
      "sources" -> ujson.Num(c.sources),
      "indexed" -> ujson.Num(c.indexed),
      "unindexed" -> ujson.Arr.from(c.unindexed.map(ujson.Str(_)))
    )

  private[mcp] def refreshWorkspaceTool(
      log: String => Unit
  ): Tool =
    McpToolsSupport.tool(
      "refresh_workspace",
      "Force-rebuild the semantic index for the current (or given) workspace root, dropping any cached copy. Not needed after a normal recompile — every tool call re-checks the *.semanticdb files on disk and rebuilds itself. Use this only to force a rebuild the on-disk check cannot see, or to rebuild a root other than the active one.",
      List(
        (
          "path",
          "string",
          "absolute or relative path to rebuild; defaults to the current workspace root"
        )
      ),
      Nil
    ) { args =>
      val rawPath = McpToolsSupport.argStr(args, "path")
      val targetPath = if (rawPath.trim.isEmpty) currentRoot else Paths.get(rawPath)
      val resolvedPath =
        (if (targetPath.isAbsolute) targetPath else currentRoot.resolve(targetPath)).normalize().nn

      if (!Files.exists(resolvedPath)) {
        sys.error(s"Path does not exist: $resolvedPath")
      }
      if (!Files.isDirectory(resolvedPath)) {
        sys.error(s"Path is not a directory: $resolvedPath")
      }

      val newState = rebuildState(resolvedPath, "forced by refresh_workspace", log)

      ujson.Obj(
        "root" -> ujson.Str(resolvedPath.toString),
        "classpath" -> newState.classpathSource.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "semanticdbFileCount" -> ujson.Num(newState.fingerprint.fileCount),
        "semanticdbNewestMtime" -> ujson.Str(
          java.time.Instant.ofEpochMilli(newState.fingerprint.newestMtimeMillis).toString
        ),
        "coverage" -> coverageJson(newState.coverage)
      )
    }

  private[mcp] def getWorkspaceRootTool: Tool =
    McpToolsSupport.tool(
      "get_workspace_root",
      "Get the current stateful workspace root path.",
      Nil,
      Nil
    ) { _ =>
      val cp = currentState.flatMap(_.classpathSource).fold[ujson.Value](ujson.Null)(ujson.Str(_))
      val fp = currentState.map(_.fingerprint)
      val cov = currentState.map(_.coverage).getOrElse(SemanticIndex.Coverage.empty)
      ujson.Obj(
        "root" -> ujson.Str(currentRoot.toString),
        "classpath" -> cp,
        "coverage" -> coverageJson(cov),
        "semanticdbFileCount" -> fp.fold[ujson.Value](ujson.Null)(f => ujson.Num(f.fileCount)),
        "semanticdbNewestMtime" -> fp.fold[ujson.Value](ujson.Null)(f =>
          ujson.Str(java.time.Instant.ofEpochMilli(f.newestMtimeMillis).toString)
        )
      )
    }
