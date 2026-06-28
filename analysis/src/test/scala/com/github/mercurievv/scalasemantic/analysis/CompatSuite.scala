package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** Cross-compiler-version robustness: the analyzer must work on SemanticDB emitted by *any* Scala,
  * not just the 3.8.4 line it is built with. Golden `*.semanticdb` for each version live under
  * `src/test/resources/compat/scala-<binVersion>/` (regenerate with `sbt compatGoldenAll`; sources
  * in `compat-fixtures/`). This suite discovers every golden dir on disk and runs the full analyzer
  * surface against each, asserting structural facts that must hold regardless of compiler version —
  * surfacing any printer/symbol-grammar gap as a failure rather than a silent empty result.
  */
class CompatSuite extends munit.FunSuite:

  private val pkg = "com/github/mercurievv/scalasemantic/compat/"

  // Locate the golden root robustly w.r.t. the process cwd. Normal `sbt test` runs unforked from the
  // repo root, but a forked runner (e.g. Stryker4s mutation testing) starts from the module dir, so a
  // single cwd-relative path is fragile. Walk up from cwd trying the known relative locations.
  private val compatRoot: Option[Path] =
    val rels = List("analysis/src/test/resources/compat", "src/test/resources/compat")
    val start = Paths.get("").toAbsolutePath
    val bases = Iterator.iterate(start)(_.getParent).takeWhile(_ != null).take(8).toList
    (for base <- bases.iterator; rel <- rels.iterator; p = base.resolve(rel) if Files.isDirectory(p)
    yield p).nextOption()

  private val versionDirs: List[Path] =
    compatRoot match
      case None => Nil
      case Some(r) =>
        val s = Files.list(r)
        try s.iterator.asScala.filter(Files.isDirectory(_)).toList.sortBy(_.getFileName.toString)
        finally s.close()

  test("golden fixtures exist for at least one non-default version"):
    assert(versionDirs.nonEmpty, "no compat golden dirs found — run `sbt compatGoldenAll`")

  versionDirs.foreach { dir =>
    val version = dir.getFileName.toString // e.g. "scala-2.13", "scala-3"
    val idx = SemanticIndex.fromRoots(Seq(dir))
    val az = Analyzer(idx)

    /** First symbol with the given display name matching the predicate. */
    def find(name: String, pred: String => Boolean): String =
      idx.symbols.keys
        .find(s => s.startsWith(pkg) && idx.displayName(s) == name && pred(s))
        .getOrElse(fail(s"[$version] no symbol named '$name'"))
    def sym(value: String): SemanticDbSymbol =
      SemanticDbSymbol.from(value).fold(fail(_), identity)
    def tpe(value: String): TypeSymbol =
      TypeSymbol.from(value).fold(fail(_), identity)
    def meth(value: String): MethodSymbol =
      MethodSymbol.from(value).fold(fail(_), identity)

    val isType = (s: String) => s.endsWith("#")
    // A global method symbol ends in `).` (incl. the `(+N)` overload disambiguator); parameter
    // symbols end in `)` with no trailing dot, so this keeps params out.
    val isMethod = (s: String) => s.endsWith(").")
    val isCtor = (s: String) => s.contains("`<init>`")

    test(s"[$version] class hierarchy: Dog <: Animal"):
      val dog = find("Dog", isType)
      val h = az.classHierarchy(tpe(dog)).getOrElse(fail(s"[$version] Dog has no hierarchy"))
      assert(
        h.parents.exists(_.displayName == "Animal"),
        s"parents=${h.parents.map(_.displayName)}"
      )

    test(s"[$version] known subtypes: Animal :> Dog, Fish"):
      val animal = find("Animal", isType)
      val subs =
        az.classHierarchy(tpe(animal)).map(_.knownSubtypes.map(_.displayName)).getOrElse(Nil)
      assert(subs.contains("Dog") && subs.contains("Fish"), s"subtypes=$subs")

    test(s"[$version] find-usages of Animal is non-empty"):
      val animal = find("Animal", isType)
      val u = az.findUsages(sym(animal))
      assert(
        (u.definitions ++ u.references).nonEmpty,
        "expected at least the definition occurrence"
      )

    test(s"[$version] method signature renders with a return type"):
      val render = find("render", isMethod)
      val sig =
        az.methodSignature(meth(render)).getOrElse(fail(s"[$version] render has no signature"))
      assert(sig.rendered.startsWith("def render"), sig.rendered)
      assertEquals(sig.returnType, "String", sig.rendered)

    test(s"[$version] overloads: over has two"):
      val over = find("over", isMethod)
      val o = az.findOverloads(meth(over))
      assertEquals(o.overloads.size, 2, o.overloads.map(_.rendered).toString)

    test(s"[$version] members: Fish declares; Robot inherits greet"):
      val fish = find("Fish", isType)
      val mf = az.members(tpe(fish)).getOrElse(fail(s"[$version] Fish has no members"))
      assert(mf.declared.nonEmpty, "expected declared members on Fish")
      val robot = find("Robot", isType)
      val mr = az.members(tpe(robot)).getOrElse(fail(s"[$version] Robot has no members"))
      assert(
        mr.inherited.exists(_.displayName == "greet"),
        s"inherited=${mr.inherited.map(_.displayName)}"
      )

    test(s"[$version] resolve-implicits: Show has candidates"):
      val show = find("Show", isType)
      val r = az.resolveImplicits(tpe(show))
      assert(r.candidates.nonEmpty, "expected at least intShow/listShow")

    test(s"[$version] call-path: Calls.a reaches Calls.c"):
      val a = find("a", isMethod)
      val c = find("c", isMethod)
      val p = az.callPath(meth(a), meth(c))
      assert(p.path.nonEmpty, "expected a -> b -> c to be reachable")

    test(s"[$version] no method signature renders an empty type"):
      idx.symbols.keys
        .filter(s => s.startsWith(pkg) && isMethod(s) && !isCtor(s))
        .flatMap(s => az.methodSignature(meth(s)))
        .foreach { sig =>
          assert(!sig.returnType.isEmpty, s"[$version] empty return type in: ${sig.symbol}")
          sig.parameterLists.flatMap(_.parameters).foreach { p =>
            assert(!p.tpe.isEmpty, s"[$version] empty param type for ${p.name} in ${sig.symbol}")
          }
        }
  }
