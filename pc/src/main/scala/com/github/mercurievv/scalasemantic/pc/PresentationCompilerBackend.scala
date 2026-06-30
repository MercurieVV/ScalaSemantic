package com.github.mercurievv.scalasemantic.pc

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import dotty.tools.pc.ScalaPresentationCompiler

import java.net.URI
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb as s
import scala.meta.pc.PresentationCompiler

/** A second SemanticDB source backed by Scala 3's in-tree presentation compiler.
  *
  * The disk SemanticDB the rest of this project indexes only exists after a clean compile: the
  * `semanticdb` phase runs after typer, so a parse/type error means no fresh payload is written for
  * that file. The presentation compiler, by contrast, recovers from errors and yields a partial
  * typed tree — so it can emit SemanticDB for a buffer that does not (yet) compile.
  *
  * [[semanticdb]] hands back a `TextDocument` in exactly the wire format [[SemanticIndex]] already
  * consumes, so a regenerated buffer splices straight in via [[SemanticIndex.withDocument]]. Bytes
  * are the interop boundary: the PC serializes with its own (compiler-bundled) SemanticDB classes,
  * we parse with scalameta's — both speak the same stable protobuf, so versions need not match.
  *
  * Construction spins up a compiler instance holding the target's `classpath`; reuse one backend
  * across calls and [[close]] it when done. Not thread-safe for concurrent calls on one buffer.
  */
final class PresentationCompilerBackend(
    classpath: Seq[Path],
    workspace: Option[Path] = None,
    options: List[String] = Nil,
    buildTargetId: String = "scala-semantic-pc"
) extends AutoCloseable:

  private val pc: PresentationCompiler =
    ScalaPresentationCompiler(folderPath = workspace)
      .newInstance(buildTargetId, classpath.map(_.toAbsolutePath).asJava, options.asJava)

  /** Generate SemanticDB for `code` as if it were the file at `fileUri` (which must point at a real
    * on-disk path the compiler can resolve), in memory, tolerant of compile errors. The emitted
    * document is keyed by `docUri` — typically how the disk index addresses this file (a path
    * relative to the project root), which differs from the absolute `fileUri` the PC needs. Pinning
    * the key lets the result splice over the matching disk document. Blocks on the compiler's
    * future.
    */
  def semanticdb(fileUri: URI, code: String, docUri: String): s.TextDocument =
    parse(pc.semanticdbTextDocument(fileUri, code).get()).copy(uri = docUri)

  /** Convenience for the case where the splice key is just the file's own uri. */
  def semanticdb(fileUri: URI, code: String): s.TextDocument =
    semanticdb(fileUri, code, fileUri.toString)

  /** Regenerate the buffer at `fileUri` (keyed by `docUri`) and overlay it onto `index`, returning
    * a new index in which that one document reflects `code` — the rest of the project untouched.
    */
  def overlay(index: SemanticIndex, fileUri: URI, code: String, docUri: String): SemanticIndex =
    index.withDocument(semanticdb(fileUri, code, docUri))

  def overlay(index: SemanticIndex, fileUri: URI, code: String): SemanticIndex =
    overlay(index, fileUri, code, fileUri.toString)

  def close(): Unit = pc.shutdown()

  /** The PC may serialize either a single `TextDocument` or a `TextDocuments` envelope depending on
    * version; accept both. `TextDocuments` is tried first (its field 1 is `repeated TextDocument`,
    * so a bare `TextDocument`'s bytes do not parse as a non-empty envelope).
    */
  private def parse(bytes: Array[Byte]): s.TextDocument =
    val envelope = scala.util.Try(s.TextDocuments.parseFrom(bytes).documents).toOption
    envelope.flatMap(_.headOption).getOrElse(s.TextDocument.parseFrom(bytes))

object PresentationCompilerBackend:

  /** A backend whose classpath is the running JVM's own. Convenient for dogfooding/tests where the
    * buffer references only what this process already has on its classpath (Scala stdlib + this
    * build's deps).
    */
  def fromCurrentJvm(
      workspace: Option[Path] = None,
      options: List[String] = Nil
  ): PresentationCompilerBackend =
    new PresentationCompilerBackend(runtimeClasspath, workspace, options)

  /** Acquire a backend for `classpath`, run `use` with it, and guarantee
    * [[PresentationCompilerBackend.close]] runs exactly once on the way out — including when `use`
    * throws. Thin wrapper over `scala.util.Using.resource`, the no-new-dependency answer to "manage
    * this lifecycle as a resource": the manual construct/`close()` pairing this replaces leaked the
    * compiler instance on any exception thrown between the two calls.
    */
  def use[A](
      classpath: Seq[Path],
      workspace: Option[Path] = None,
      options: List[String] = Nil
  )(use: PresentationCompilerBackend => A): A =
    scala.util.Using.resource(new PresentationCompilerBackend(classpath, workspace, options))(use)

  /** [[use]], sourcing the classpath from the running JVM (mirrors [[fromCurrentJvm]]). The
    * preferred entry point for dogfooding/tests that only need the backend for the duration of one
    * call.
    */
  def useCurrentJvm[A](
      workspace: Option[Path] = None,
      options: List[String] = Nil
  )(use: PresentationCompilerBackend => A): A =
    scala.util.Using.resource(fromCurrentJvm(workspace, options))(use)

  /** The running process's classpath, from two sources unioned because neither alone is reliable:
    *   - `java.class.path` — correct for a plain `java -cp …` launch (and `sbt run`/`runMain`).
    *   - the classloader chain's `URLClassLoader` URLs — needed under sbt's forked TEST runner,
    *     which puts only its test-agent on `java.class.path` and loads the real classpath into a
    *     child loader.
    */
  private def runtimeClasspath: Seq[Path] =
    val fromProp = System
      .getProperty("java.class.path", "")
      .split(java.io.File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)
      .flatMap(p => scala.util.Try(java.nio.file.Paths.get(p)).toOption)
    val fromLoaders = Iterator
      .unfold(Thread.currentThread.getContextClassLoader: ClassLoader)(loader =>
        Option(loader).map(current => current -> current.getParent)
      )
      .collect { case u: java.net.URLClassLoader => u.getURLs.toSeq }
      .flatten
      .flatMap(url => scala.util.Try(java.nio.file.Paths.get(url.toURI)).toOption)
    (fromProp ++ fromLoaders).toVector.distinct
