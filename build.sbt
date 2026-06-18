ThisBuild / scalaVersion := "3.8.4"

ThisBuild / scalafixDependencies += "org.typelevel" %% "typelevel-scalafix" % "0.5.0"

// Shared across all modules: SemanticDB emission (for dogfooding + scalafix) and wart rules.
lazy val commonSettings = Seq(
  scalacOptions += "-Wunused:imports", // required by OrganizeImports scalafix rule
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
  )
)

lazy val munit = "org.scalameta" %% "munit" % "1.2.3" % Test
lazy val upickle = "com.lihaoyi" %% "upickle" % "4.2.1"

// core: load + index SemanticDB and expose symbol-grammar primitives. No JSON, no MCP.
// scalameta + its SemanticDB bindings are JVM-published on the 2.13 line; consume them from
// Scala 3 via for3Use2_13 (as scalafix does). `semanticdb-shared` carries the
// scala.meta.internal.semanticdb.* protobuf classes (not pulled in by `scalameta`).
lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-core",
    libraryDependencies ++= Seq(
      ("org.scalameta" %% "scalameta" % "4.13.9").cross(CrossVersion.for3Use2_13),
      ("org.scalameta" %% "semanticdb-shared" % "4.13.9").cross(CrossVersion.for3Use2_13),
      munit
    )
  )

// analysis: the query engine + result models (upickle), built on core.
lazy val analysis = (project in file("analysis"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-analysis",
    libraryDependencies ++= Seq(upickle, munit)
  )

// mcp: stdio JSON-RPC server + entrypoint. Test-depends on analysis so its fixtures (and their
// SemanticDB) are compiled before the MCP tests, which dogfood on the whole repo's SemanticDB.
lazy val mcp = (project in file("mcp"))
  .dependsOn(analysis % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-mcp",
    libraryDependencies ++= Seq(upickle, munit)
  )

lazy val root = (project in file("."))
  .aggregate(core, analysis, mcp)
  .settings(name := "ScalaSemanticMCP")

// Pre-push gate. A command alias (not a task) so clean/format/fix/test aggregate across all
// modules. `testOnly *` forces the full suite (sbt 2.0 `test` is cached testQuick — see PLAN.md).
addCommandAlias("prePush", "clean; scalafmtAll; scalafixAll; Test/testOnly *")
