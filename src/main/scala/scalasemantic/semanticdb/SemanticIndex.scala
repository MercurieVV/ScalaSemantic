package scalasemantic.semanticdb

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb as s

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

  private val docByUri: Map[String, s.TextDocument] =
    documents.map(d => d.uri -> d).toMap

  def document(uri: String): Option[s.TextDocument] = docByUri.get(uri)

  def info(symbol: String): Option[s.SymbolInformation] = symbols.get(symbol)

  /** Human-readable name for a symbol: prefer the indexed display name, otherwise decode it from
    * the trailing descriptor of the symbol string.
    */
  def displayName(symbol: String): String =
    symbols.get(symbol).map(_.displayName).filter(_.nonEmpty).getOrElse(decodeName(symbol))

  /** Owner symbol (enclosing scope) of a global symbol, or "" for top-level/_root_. */
  def owner(symbol: String): String =
    val trimmed = symbol.stripSuffix("#").stripSuffix(".").stripSuffix(")").stripSuffix("/")
    val cut = symbol.length - (symbol.length - lastDescriptorStart(symbol))
    val idx = lastDescriptorStart(symbol)
    if idx <= 0 then "" else symbol.substring(0, idx)

  private def lastDescriptorStart(symbol: String): Int =
    // descriptors end in one of: # . / ) — find start of the last one
    val end = symbol.length
    // skip a trailing method disambiguator like "(+1)" or "()."
    var i = end - 1
    // find boundary: previous descriptor terminator before the final segment
    val terminators = Set('#', '.', '/', ')')
    // drop final terminator(s)
    while i >= 0 && terminators.contains(symbol.charAt(i)) do i -= 1
    while i >= 0 && !terminators.contains(symbol.charAt(i)) do i -= 1
    i + 1

  private def decodeName(symbol: String): String =
    val start = lastDescriptorStart(symbol)
    val seg = symbol.substring(start).takeWhile(c => c != '#' && c != '.' && c != '/' && c != '(')
    if seg.nonEmpty then seg else symbol

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
      val stream = Files.walk(root)
      try
        stream.iterator.asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".semanticdb"))
          .toVector
      finally stream.close()
