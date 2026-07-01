// sbt 2.0 meta-build is Scala 3; some plugins still drag _2.13 scala-collection-compat.
// Let coursier pick a single suffix instead of failing the cross-version check.
ThisBuild / conflictWarning := ConflictWarning.disable

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("org.wartremover" %% "sbt-wartremover" % "3.6.0")
// dynver + pgp + sonatype (Central Portal) — drives tag-based releases via the `ci-release` command.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
// fat-jar for the standalone `java -jar` MCP launcher attached to GitHub Releases.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
// expose the dynver-derived version to the server at runtime (BuildInfo) instead of hardcoding it.
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
libraryDependencies += "com.guardsquare" % "proguard-base" % "7.9.1"
// Mutation testing. The published sbt-stryker4s (0.21.0) is built against sbt 2.0.0-RC2 and its ABI
// fails to load on sbt 2.0.0 final. Stryker4s MASTER already pins the plugin to sbt 2.0.0 final, so
// we consume a locally-published build of master (`sbtPlugin3/publishLocal` in a stryker4s clone)
// until a release ships.
//
// That snapshot lives ONLY in the local ivy cache — it is on no remote repo, so loading it
// unconditionally breaks every environment that lacks the publishLocal (notably CI: the meta-build
// can't resolve it and the whole build fails before any task runs). So gate it behind a flag and
// enable it only when actually mutation-testing:
//   sbt -Dstryker=true "analysis/stryker"      (config in stryker4s.conf)
if (sys.props.get("stryker").contains("true") || sys.env.contains("STRYKER"))
  Seq(
    addSbtPlugin(
      "io.stryker-mutator" % "sbt-stryker4s" % sys.props
        .get("stryker4s.version")
        .orElse(sys.env.get("STRYKER4S_PLUGIN_VERSION"))
        .getOrElse("0.21.0+33-2c0f5bd7-SNAPSHOT")
    )
  )
else Seq.empty[Setting[?]]

// SLF4J NOP binding to silence the StaticLoggerBinder warning during sbt initialization.
// The warning occurs because io.get-coursier:interface pulls in org.slf4j:slf4j-api, and without
// a binding implementation, SLF4J defaults to NOP and prints a warning to stderr.
libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.36"

// Stainless formal verification (issue #68).
// `sbt-stainless` from https://github.com/epfl-lara/stainless/releases/tag/v0.9.9.3 ships only
// an sbt-1.x plugin JAR (compiled against Scala 2.12 / sbt 1.x ABI); loading it under sbt 2.0
// throws `Binary incompatibility in plugins detected`.  Verification is therefore driven by the
// standalone Stainless tool instead (see analysis/lib/stainless-library.jar and the stainless/
// local ivy snapshot).  If a future Stainless release publishes an sbt-2.0-compatible plugin,
// re-enable with:
//   addSbtPlugin("ch.epfl.lara" % "sbt-stainless" % "<version>")
// and set `stainlessEnabled := true` in the analysis module's settings.

