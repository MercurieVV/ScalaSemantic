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

  /** Recursively scan `roots` for `*.semanticdb` files and load them. */
  def fromRoots(roots: Seq[Path]): SemanticIndex =
    val files = roots.filter(Files.exists(_)).flatMap(findSemanticdb)
    val docs = files.flatMap { f =>
      val bytes = Files.readAllBytes(f)
      s.TextDocuments.parseFrom(bytes).documents
    }.toVector
    new SemanticIndex(docs)

  def fromProject(projectRoot: String): SemanticIndex =
    fromRoots(Seq(Paths.get(projectRoot)))

  private def findSemanticdb(root: Path): Seq[Path] =
    if !Files.exists(root) then Nil
    else
      val result = new java.util.ArrayList[Path]()
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            val name = Option(dir.getFileName)
            name match
              case Some(n) =>
                val nameStr = n.toString
                if dir != root && nameStr != "." && nameStr != ".." && (nameStr.startsWith(
                    "."
                  ) || nameStr == "worktrees")
                then FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE
              case None =>
                FileVisitResult.CONTINUE

          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            if file.getFileName.toString.endsWith(".semanticdb") && Files.isRegularFile(file) then
              val _ = result.add(file)
            FileVisitResult.CONTINUE

          override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
            FileVisitResult.CONTINUE
      )
      result.asScala.toSeq
