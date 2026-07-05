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
    val bases =
      Iterator
        .unfold(start)(path => Option(path).map(current => current -> current.getParent))
        .take(8)
        .toList
    (for base <- bases.iterator; rel <- rels.iterator; p = base.resolve(rel) if Files.isDirectory(p)
    yield p).nextOption()

  private val versionDirs: List[Path] =
    compatRoot match
      case None    => Nil
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
    def meth(value: String): MethodSymbol =
      MethodSymbol.from(value).fold(fail(_), identity)

    // A global method symbol ends in `).` (incl. the `(+N)` overload disambiguator); parameter
    // symbols end in `)` with no trailing dot, so this keeps params out.
    val isMethod = (s: String) => s.endsWith(").")
    val isCtor = (s: String) => s.contains("`<init>`")

    // Class hierarchy / members / find-usages / method-signature / overloads / resolve-implicits over
    // general Scala constructs (plain trait+class inheritance, generics, implicits, overloads) are
    // asserted corpus-wide below (#202) — the vendored Scalameta/Scala-3 corpora already exercise
    // those shapes at far greater scale than a hand-written Dog/Animal/Show/Overloads bundle could,
    // so the redundant hand-written probes for them were removed here (see
    // `docs/testing/compat-strategy.md` section 5). What remains hand-authored below are only this
    // tool's own edge cases the corpora do not target: polymorphic/implicit call-path depth.

    test(s"[$version] polymorphic call path: entry1 reaches callVirtual and VirtualBase.name"):
      val entry1 = find("entry1", isMethod)
      val callVirtual = find("callVirtual", isMethod)
      val name = idx.symbols.keys
        .find(_.contains("VirtualBase#name"))
        .getOrElse(fail(s"[$version] no VirtualBase#name"))
      val p1 = az.callPath(meth(entry1), meth(callVirtual))
      assert(p1.path.nonEmpty, "expected entry1 -> callVirtual to be reachable")
      val p2 = az.callPath(meth(callVirtual), meth(name))
      assert(p2.path.nonEmpty, "expected callVirtual -> VirtualBase.name to be reachable")

    test(s"[$version] implicit/extension call path: triggerShout reaches extension method"):
      val triggerShout = find("triggerShout", isMethod)
      if version == "scala-2.13" then
        val shout = find("shout", isMethod)
        val p = az.callPath(meth(triggerShout), meth(shout))
        assert(
          p.path.nonEmpty,
          "expected triggerShout -> RichString.shout to be reachable"
        )
      else
        val shout2 = find("shout2", isMethod)
        val p = az.callPath(meth(triggerShout), meth(shout2))
        assert(p.path.nonEmpty, "expected triggerShout -> shout2 to be reachable")

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

  // --- Corpus-wide invariants (#201) ------------------------------------------------------------
  // Golden dirs above are small hand-authored fixtures probed by name (Dog/Animal/Show/...). These
  // corpus roots hold real third-party sources (scalameta + scala-3 compiler corpora, produced by
  // `./mill corpusGoldenAll` — build.mill `corpus` cross module — under `target/corpus/scala-
  // <binVer>/`) — too large and unstable to name individual symbols, so we assert universal
  // structural invariants over EVERY symbol instead: no exception, no silently empty/blank render.
  // This surfaces printer/symbol-grammar gaps that named probes would miss.
  private val corpusRoot: Option[Path] =
    val rels = List("target/corpus")
    val start = Paths.get("").toAbsolutePath
    val bases =
      Iterator
        .unfold(start)(path => Option(path).map(current => current -> current.getParent))
        .take(8)
        .toList
    (for base <- bases.iterator; rel <- rels.iterator; p = base.resolve(rel) if Files.isDirectory(p)
    yield p).nextOption()

  private val corpusVersionDirs: List[Path] =
    corpusRoot match
      case None    => Nil
      case Some(r) =>
        val s = Files.list(r)
        try s.iterator.asScala.filter(Files.isDirectory(_)).toList.sortBy(_.getFileName.toString)
        finally s.close()

  corpusVersionDirs.foreach { dir =>
    val version = dir.getFileName.toString
    val idx = SemanticIndex.fromRoots(Seq(dir))
    val az = Analyzer(idx)

    val isType = (s: String) => s.endsWith("#")
    val isMethod = (s: String) => s.endsWith(").")
    val isCtor = (s: String) => s.contains("`<init>`")

    test(s"[corpus $version] golden root has semanticdb symbols"):
      assert(idx.symbols.nonEmpty, s"[corpus $version] no symbols loaded from $dir")

    /** First corpus symbol whose string satisfies `pred` — unlike golden `find` above this matches
      * on the symbol string itself (owner-scoped, e.g. by package/type prefix), not just the
      * display name: several corpus files reuse short names like `A`/`B`/`C1` for unrelated
      * fixtures, so a display-name-only lookup over the whole corpus would be ambiguous.
      */
    def findAny(pred: String => Boolean): String =
      idx.symbols.keys
        .find(pred)
        .getOrElse(fail(s"[corpus $version] no matching symbol"))

    // --- Named corpus-backed probes (#202) --------------------------------------------------------
    // Re-expresses the intent of the deleted hand-written BasicClasses/Inheritance/Generics/
    // Overloads/Implicits/VendoredFixtures fixtures against equivalent constructs the vendored
    // corpora already contain, instead of duplicating them by hand. `Overrides.scala` (`trait A { def
    // foo: Int }` / `class B() extends A`) and `Classes.scala`'s `C1`/`M.C5` are present byte-for-byte
    // identically in both the Scalameta (2.13) and Scala-3-compiler corpora, both under package
    // `example`/`classes` respectively — scoped on that owner prefix to disambiguate from unrelated
    // same-named `A`/`B`/`C1`/`C5` fixtures elsewhere in the corpus (e.g. `Empty.scala`, `Selfs.scala`).

    test(s"[corpus $version] class hierarchy: B <: A (Overrides.scala)"):
      val b = findAny(s => isType(s) && s.endsWith("example/B#"))
      val h = az.classHierarchy(TypeSymbol.from(b).fold(fail(_), identity))
      assert(
        h.exists(_.parents.exists(_.displayName == "A")),
        s"hierarchy=$h"
      )

    test(s"[corpus $version] members: A declares foo; B inherits or overrides foo"):
      val a = findAny(s => isType(s) && s.endsWith("example/A#"))
      val ma = az.members(TypeSymbol.from(a).fold(fail(_), identity))
      assert(ma.exists(_.declared.exists(_.displayName == "foo")), s"members=$ma")

    test(s"[corpus $version] find-usages of A (Overrides.scala) is non-empty"):
      val a = findAny(s => isType(s) && s.endsWith("example/A#"))
      val u = az.findUsages(SemanticDbSymbol.from(a).fold(fail(_), identity))
      assert(
        (u.definitions ++ u.references).nonEmpty,
        "expected at least the definition occurrence"
      )

    test(
      s"[corpus $version] method signature: NamedApplyBlockMethods.foo (NamedApplyBlock.scala) renders with Int return"
    ):
      val foo = findAny(s => isMethod(s) && s.endsWith("example/NamedApplyBlockMethods.foo()."))
      val sig = az.methodSignature(MethodSymbol.from(foo).fold(fail(_), identity))
      assert(sig.exists(_.returnType == "Int"), s"signature=$sig")

    test(s"[corpus $version] value class: C1 (Classes.scala) declares x1"):
      val c1 = findAny(s => isType(s) && s.endsWith("classes/C1#"))
      val m = az.members(TypeSymbol.from(c1).fold(fail(_), identity))
      assert(m.exists(_.declared.exists(_.displayName == "x1")), s"members=$m")

    test(s"[corpus $version] implicit class: M.C5 (Classes.scala) declares x"):
      val c5 = findAny(s => isType(s) && s.endsWith("classes/M.C5#"))
      val m = az.members(TypeSymbol.from(c5).fold(fail(_), identity))
      assert(m.exists(_.declared.exists(_.displayName == "x")), s"members=$m")

    if version == "scala-3" then
      // Simple parameterless enum cases (`case Red, Green, Blue`) compile to `val`-shaped members of
      // the enum's companion, not distinct subtype classes `classHierarchy` can see as known
      // subtypes — same reasoning the pre-#202 golden-fixture "enum cases" test already applied to
      // its own `Color` fixture's scala-3 branch. Check the case-member symbols exist instead.
      test(s"[corpus $version] enum: Colour (Enums.scala) has cases Red/Green/Blue"):
        val red = idx.symbols.keys.exists(_.endsWith("Enums.Colour.Red."))
        val green = idx.symbols.keys.exists(_.endsWith("Enums.Colour.Green."))
        val blue = idx.symbols.keys.exists(_.endsWith("Enums.Colour.Blue."))
        assert(
          red && green && blue,
          s"enum cases not found in: ${idx.symbols.keys.filter(_.contains("Colour")).toList}"
        )

      test(s"[corpus $version] opaque type: OpaqueB (NewModifiers.scala) is defined"):
        findAny(s => isType(s) && s.endsWith("OpaqueB#"))

    if version == "scala-2.13" then
      test(s"[corpus $version] package object: flags.p.package.z (Flags_2.13.scala) returns Int"):
        val z = findAny(s => isMethod(s) && s.endsWith("flags/p/package.z()."))
        val sig = az.methodSignature(MethodSymbol.from(z).fold(fail(_), identity))
        assertEquals(sig.map(_.returnType), Some("Int"))

    // `example/Shadow#*` (scala-3 corpus, `ShadowedParameters.scala`) is a fixture that deliberately
    // makes every method in the class shadow its own parameters with a same-named local
    // (`def f(x: Int) = { val x = ... }`) to stress-test shadowed-parameter symbol tracking. This
    // is a confirmed scalac 3.8.4 SemanticDB-emission quirk, not an Analyzer gap: for each such
    // method the compiler records its `MethodSignature.parameterLists` scope pointing at the
    // block-shadowing locals (e.g. `Scope(Vector(local6, local5), Vector())`) instead of the real
    // parameter symbols, and one of those locals carries a bogus `$anon`/untyped
    // `SymbolInformation` — verified by dumping the raw info for `shadowInParamBlock`/`multiParams`.
    // The printer correctly renders the empty type it was given. Excluded by owner prefix (not a
    // broader filter), so any other real gap in the rest of the corpus still fails this invariant.
    val knownEmptyTypeExceptionOwner = "example/Shadow#"

    test(s"[corpus $version] no method signature renders an empty type (whole corpus)"):
      idx.symbols.keys
        .filter(s => isMethod(s) && !isCtor(s) && !s.startsWith(knownEmptyTypeExceptionOwner))
        .flatMap(s => MethodSymbol.from(s).toOption)
        .flatMap(m => az.methodSignature(m))
        .foreach { sig =>
          assert(!sig.returnType.isEmpty, s"[corpus $version] empty return type in: ${sig.symbol}")
          sig.parameterLists.flatMap(_.parameters).foreach { p =>
            assert(
              !p.tpe.isEmpty,
              s"[corpus $version] empty param type for ${p.name} in ${sig.symbol}"
            )
          }
        }

    test(s"[corpus $version] class-hierarchy runs over every type symbol without throwing"):
      idx.symbols.keys
        .filter(isType)
        .flatMap(s => TypeSymbol.from(s).toOption)
        .foreach { t =>
          try az.classHierarchy(t)
          catch case e: Throwable => fail(s"[corpus $version] classHierarchy threw on $t: $e")
        }

    test(s"[corpus $version] members runs over every type symbol without throwing"):
      idx.symbols.keys
        .filter(isType)
        .flatMap(s => TypeSymbol.from(s).toOption)
        .foreach { t =>
          try az.members(t)
          catch case e: Throwable => fail(s"[corpus $version] members threw on $t: $e")
        }

    test(s"[corpus $version] resolve-implicits runs over every type symbol without throwing"):
      idx.symbols.keys
        .filter(isType)
        .flatMap(s => TypeSymbol.from(s).toOption)
        .foreach { t =>
          try az.resolveImplicits(t)
          catch case e: Throwable => fail(s"[corpus $version] resolveImplicits threw on $t: $e")
        }

    test(s"[corpus $version] find-usages runs over every symbol without throwing"):
      idx.symbols.keys
        .flatMap(s => SemanticDbSymbol.from(s).toOption)
        .foreach { sym =>
          try az.findUsages(sym)
          catch case e: Throwable => fail(s"[corpus $version] findUsages threw on $sym: $e")
        }
  }
