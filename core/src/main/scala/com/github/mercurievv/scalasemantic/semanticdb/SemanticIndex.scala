package com.github.mercurievv.scalasemantic.semanticdb

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb as s
import scala.meta.internal.semanticdb.Scala.*
import scala.meta.internal.semanticdb.Scala.Symbols

/** Loads SemanticDB payloads from a project and indexes them for querying.
  *
  * SemanticDB files are emitted by the compiler (`-Xsemanticdb` / `semanticdbEnabled`) under
  * `META-INF/semanticdb/<sourcepath>.semanticdb`. Each file is a protobuf `TextDocuments` carrying,
  * per source file: the symbol table ([[s.SymbolInformation]]) and the occurrence list
  * ([[s.SymbolOccurrence]]) — exactly the data Scalafix queries through its `SemanticDocument`.
  */
final class SemanticIndex(val documents: Vector[s.TextDocument]):

  /** Global symbol -> its information, last definition wins. */
  val symbols: Map[String, s.SymbolInformation] =
    documents.flatMap(_.symbols).map(si => si.symbol -> si).toMap

  /** All occurrences flattened, tagged with the document uri they came from. */
  val occurrences: Vector[(String, s.SymbolOccurrence)] =
    documents.flatMap(d => d.occurrences.map(d.uri -> _))

  /** Occurrences grouped by their symbol, so per-symbol queries (usages, rename, definition uri)
    * are a map lookup instead of a full scan of [[occurrences]].
    */
  val occurrencesBySymbol: Map[String, Vector[(String, s.SymbolOccurrence)]] =
    occurrences.groupBy(_._2.symbol)

  /** Occurrences of one symbol across all documents (empty if none). */
  def occurrencesOf(symbol: String): Vector[(String, s.SymbolOccurrence)] =
    occurrencesBySymbol.getOrElse(symbol, Vector.empty)

  private val docByUri: Map[String, s.TextDocument] =
    documents.map(d => d.uri -> d).toMap

  def document(uri: String): Option[s.TextDocument] = docByUri.get(uri)

  def info(symbol: String): Option[s.SymbolInformation] = symbols.get(symbol)

  /** A new index with `doc` overlaid: any existing document for the same uri is dropped and
    * replaced. This is the splice point for a freshly (re)generated buffer — e.g. SemanticDB
    * emitted in-memory by the presentation compiler for a file edited since the last compile. All
    * derived state (`symbols`, `occurrences`, …) is recomputed in the returned index.
    */
  def withDocument(doc: s.TextDocument): SemanticIndex =
    new SemanticIndex(documents.filterNot(_.uri == doc.uri) :+ doc)

  // --- Symbol grammar -------------------------------------------------------
  //
  // A SemanticDB global symbol is `Owner Descriptor`, where each descriptor encodes
  // both the simple name and the kind via its terminator:
  //   package `foo/`   type `Foo#`   term `foo.`   method `foo().` (with `(+1)` disambig)
  //   parameter `(x)`   type-parameter `[T]`.   Names may be backtick-escaped.
  // Local symbols are `local<N>`. Rather than re-parse this by hand we delegate to the
  // official `scala.meta.internal.semanticdb.Scala` helpers — the same ones Scalafix uses.

  /** Human-readable name: prefer the indexed display name, else the last descriptor's name. */
  def displayName(symbol: String): String =
    symbols
      .get(symbol)
      .map(_.displayName)
      .filter(_.nonEmpty)
      .getOrElse(if symbol.isGlobal then symbol.desc.name.value else symbol)

  /** Owner symbol (enclosing scope), or "" for top-level / root / local symbols. */
  def owner(symbol: String): String =
    val o = symbol.owner
    if o == Symbols.RootPackage || o == Symbols.EmptyPackage || o == Symbols.None then "" else o

  /** Enclosing scopes from outermost to the symbol itself (excludes the root package). */
  def ownerChain(symbol: String): List[String] =
    symbol.ownerChain.filterNot(s => s == Symbols.RootPackage || s == Symbols.EmptyPackage)

  def isGlobal(symbol: String): Boolean = symbol.isGlobal
  def isLocal(symbol: String): Boolean = symbol.isLocal
  def isMethod(symbol: String): Boolean = symbol.isGlobal && symbol.desc.isMethod
  def isType(symbol: String): Boolean = symbol.isGlobal && symbol.isType
  def isTerm(symbol: String): Boolean = symbol.isGlobal && symbol.isTerm
  def isPackage(symbol: String): Boolean = symbol.isGlobal && symbol.isPackage

object SemanticIndex:

  /** A cheap staleness signature for a set of `*.semanticdb` files: how many there are and the
    * newest last-modified time among them. Two scans of an unchanged tree produce equal
    * fingerprints; a recompile that touches any file changes at least one of the two fields.
    */
  final case class Fingerprint(fileCount: Int, newestMtimeMillis: Long)

  /** Fingerprint the `*.semanticdb` files reachable under `roots`, without parsing them. Used to
    * detect whether a previously loaded [[SemanticIndex]] is stale relative to disk.
    */
  def fingerprint(roots: Seq[Path]): Fingerprint =
    val files = roots.filter(Files.exists(_)).flatMap(findSemanticdb)
    val mtimes = files.map(f => Files.getLastModifiedTime(f).toMillis)
    Fingerprint(files.size, mtimes.foldLeft(0L)(_ max _))

  /** How much of the project's Scala source the index actually covers.
    *
    * The server otherwise scans `*.semanticdb` only, so a source file that was never compiled with
    * SemanticDB enabled (a test scope left out of the build, a module not compiled yet) is
    * indistinguishable from a source file that does not exist — and a query about a symbol defined
    * there returns a bare `count: 0` that reads as "does not exist" rather than "not indexed".
    *
    * `unindexed` is capped ([[Coverage.MaxListed]]) so the report stays payload-sized; `sources -
    * indexed` is the true gap.
    */
  final case class Coverage(sources: Int, indexed: Int, unindexed: Seq[String]):
    def partial: Boolean = indexed < sources

  object Coverage:
    val MaxListed = 20
    val empty: Coverage = Coverage(0, 0, Nil)

  /** Compare the `*.scala` files on disk under `roots` against the documents `index` actually
    * loaded.
    *
    * Matching is done against the loaded documents' `uri`s rather than a guessed
    * `META-INF/semanticdb/<rel>.semanticdb` target path: the uri is whatever the compiler recorded
    * relative to its `sourceroot`, which is not always the project root, so equality on either side
    * of a path-suffix relation is the only reliable test.
    */
  def coverage(roots: Seq[Path], index: SemanticIndex): Coverage =
    val docUris = index.documents.map(_.uri).filter(_.nonEmpty)
    // Bucket by file name so each source is checked against same-named uris only, not all of them.
    val byName = docUris.groupBy(u => u.substring(u.lastIndexOf('/') + 1))
    val sources = roots
      .filter(Files.exists(_))
      .flatMap { root =>
        val base = root.toAbsolutePath.normalize().nn
        findFiles(base, ".scala").map(p => relativeUri(base, p))
      }
      .distinct
    val unindexed = sources.filterNot { rel =>
      val name = rel.substring(rel.lastIndexOf('/') + 1)
      byName.getOrElse(name, Vector.empty).exists(u => isSamePath(u, rel))
    }
    Coverage(sources.size, sources.size - unindexed.size, unindexed.take(Coverage.MaxListed))

  private def relativeUri(root: Path, file: Path): String =
    val rel = scala.util.Try(root.relativize(file).nn.toString).getOrElse(file.toString)
    rel.replace(java.io.File.separator, "/")

  /** Equal paths, or one a path-segment suffix of the other (different `sourceroot` depths). */
  private def isSamePath(a: String, b: String): Boolean =
    a == b || a.endsWith("/" + b) || b.endsWith("/" + a)

  /** Recursively scan `roots` for `*.semanticdb` files and load them. */
  def fromRoots(roots: Seq[Path]): SemanticIndex =
    val files = roots.filter(Files.exists(_)).flatMap(findSemanticdb)
    val docs = files.flatMap { f =>
      try
        val bytes = Files.readAllBytes(f)
        s.TextDocuments.parseFrom(bytes).documents
      catch case _: IOException => Nil
    }.toVector
    new SemanticIndex(docs)

  def fromProject(projectRoot: String): SemanticIndex =
    fromRoots(Seq(Paths.get(projectRoot)))

  private def findSemanticdb(root: Path): Seq[Path] = findFiles(root, ".semanticdb")

  /** Build-output directories that hold generated or copied Scala sources. Skipped when scanning
    * for sources, but NOT when scanning for `*.semanticdb` — that is exactly where the compiler
    * emits them.
    */
  private val SourceScanSkip = Set("out", "target")

  /** Never holds project SemanticDB or project sources, and is routinely the largest directory in
    * the tree — on this repo it is 80% of the walk (40k of 50k files), which matters because the
    * fingerprint walk now runs on every request.
    */
  private val AlwaysSkip = Set("node_modules")

  private def findFiles(root: Path, suffix: String): Seq[Path] =
    val skipBuildOutput = suffix != ".semanticdb"
    if !Files.exists(root) then Nil
    else
      val result = new java.util.ArrayList[Path]()
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            val skip = Option(dir.getFileName).exists { n =>
              val nameStr = n.toString
              val hiddenCache = nameStr.startsWith(".") && nameStr != ".semanticdb"
              val buildOutput = skipBuildOutput && SourceScanSkip.contains(nameStr)
              dir != root && nameStr != "." && nameStr != ".." &&
              (hiddenCache || nameStr == "worktrees" || buildOutput ||
                AlwaysSkip.contains(nameStr))
            }
            if skip then FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            if file.getFileName.toString.endsWith(suffix) && Files.isRegularFile(file) then
              val _ = result.add(file)
            FileVisitResult.CONTINUE

          override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
            FileVisitResult.CONTINUE
      )
      // Files.walkFileTree's visit order mirrors raw OS directory-entry order (unsorted) and
      // differs between filesystems (e.g. ext4 on Linux CI vs APFS on macOS). Duplicate symbol IDs
      // do occur in this index (synthetic `local<N>` compiler counters are reused per-file, not
      // globally unique) and downstream logic tie-breaks on `documents` order ("last definition
      // wins" in `symbols`, first-found in `occurrences`/`moduleOf`), so an unsorted walk makes the
      // whole index's answers filesystem-dependent. Sorting here pins one deterministic order.
      result.asScala.toSeq.sortBy(_.toString)
