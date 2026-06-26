// sbt 2.0 meta-build is Scala 3; some plugins still drag _2.13 scala-collection-compat.
// Let coursier pick a single suffix instead of failing the cross-version check.
ThisBuild / conflictWarning := ConflictWarning.disable

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.5")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
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
// until a release ships. Run with `sbt "analysis/stryker"`; config in stryker4s.conf.
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.0.0+1-2c0f5bd7-SNAPSHOT")