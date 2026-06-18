ThisBuild / scalaVersion := "3.8.4"

ThisBuild / scalafixDependencies += "org.typelevel" %% "typelevel-scalafix" % "0.5.0"

lazy val prePush = taskKey[Unit]("Run all checks: format, fix, clean compile, test")

lazy val root = (project in file("."))
  .settings(
    name := "ScalaSemanticMCP",
    scalacOptions += "-Wunused:imports", // required by OrganizeImports scalafix rule
    // emit SemanticDB for this project so we can dogfood the analyzer on itself
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    wartremoverWarnings ++= Seq(
      Wart.Var,
      Wart.MutableDataStructures,
      Wart.NonUnitStatements,
      Wart.Throw,
      Wart.Return,
      Wart.AsInstanceOf,
      Wart.IsInstanceOf,
      Wart.Null
    ),
    libraryDependencies ++= Seq(
      // scalameta + its SemanticDB bindings are JVM-published on the 2.13 line; consume
      // them from Scala 3 via for3Use2_13 (same approach scalafix uses). Keeping both on
      // the 2.13 suffix avoids cross-version conflicts. `semanticdb-shared` carries the
      // scala.meta.internal.semanticdb.* protobuf classes (not pulled in transitively).
      ("org.scalameta" %% "scalameta"          % "4.13.9").cross(CrossVersion.for3Use2_13),
      ("org.scalameta" %% "semanticdb-shared"  % "4.13.9").cross(CrossVersion.for3Use2_13),
      "com.lihaoyi"   %% "upickle"   % "4.2.1",
      "org.scalameta" %% "munit"     % "1.2.3" % Test
    ),
    prePush := Def.uncached(
      Def
        .sequential(
          clean,
          scalafmtAll,
          scalafixAll.toTask(""),
          // `Test / test` is cached `testQuick` under sbt 2.0 and skips unchanged passing
          // tests (reports "Total 0"); `testOnly *` forces the full suite every push.
          (Test / testOnly).toTask(" *")
        )
        .value
    )
  )