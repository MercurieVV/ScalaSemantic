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