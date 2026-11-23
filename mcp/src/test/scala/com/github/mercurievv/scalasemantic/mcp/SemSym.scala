package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

/** Fixture coordinates that survive moving code between packages and modules: a test names the type
  * or the file it needs, never where it currently lives.
  */
object SemSym:

  /** The SemanticDB symbol of a top-level class/trait/enum, taken from the class itself. */
  def typeOf[A](using ct: ClassTag[A]): String = ct.runtimeClass.getName.replace('.', '/') + "#"

  private val Roots = List("core", "pc", "domain", "analysis", "wire", "mcp", "launcher")

  /** The repo-relative path of the one main source file called `name`, wherever it lives. */
  def mainFile(name: String): String =
    val found = Roots.flatMap: r =>
      val dir: Path = Paths.get(r, "src", "main")
      if Files.isDirectory(dir) then
        val s = Files.walk(dir)
        try s.iterator.asScala.filter(_.getFileName.toString == name).map(_.toString).toList
        finally s.close()
      else Nil
    found match
      case one :: Nil => one
      case other      => sys.error(s"expected exactly one main source named $name, found $other")
