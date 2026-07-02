package com.github.mercurievv.scalasemantic.sbtplugin

import ScalaSemanticConfigMerger.mergeJson
import ScalaSemanticConfigMerger.mergeToml
import ScalaSemanticConfigMerger.mergeYaml

/** Unit tests for the dependency-free config mergers in [[ScalaSemanticMcpPlugin]]. They exercise
  * the three on-disk formats (JSON / TOML / YAML) for: a fresh file, adding alongside an existing
  * third-party MCP server, replacing our own entry, preserving unrelated content, and — crucially —
  * idempotency (re-running the merge on its own output must be a fixpoint).
  */
class ConfigMergeSuite extends munit.ScalaCheckSuite {

  private val name = "scala-semantic"
  private val argv =
    Seq("/bin/launch.sh", "/proj", "/cp.txt", "--log", "--log-output")

  /** Assert that applying `merge` to its own output twice more changes nothing. */
  private def assertIdempotent(
      label: String,
      merge: Option[String] => String,
      seed: Option[String]
  )(implicit
      loc: munit.Location
  ): Unit = {
    val once = merge(seed)
    val twice = merge(Some(once))
    val thrice = merge(Some(twice))
    assertEquals(twice, once, s"$label: 2nd merge differs from 1st")
    assertEquals(thrice, twice, s"$label: 3rd merge differs from 2nd")
  }

  // --- JSON -------------------------------------------------------------------------------------

  test("json: fresh file gets a well-formed mcpServers entry") {
    val out = mergeJson(None, name, argv, Nil)
    assert(out.contains("\"mcpServers\""))
    assert(out.contains("\"scala-semantic\""))
    assert(out.contains("\"/bin/launch.sh\""))
    assert(out.contains("\"--log-output\""))
  }

  test("json: adds alongside an existing server and keeps unrelated root keys") {
    val existing =
      """{
        |  "someOtherKey": true,
        |  "mcpServers": {
        |    "other": { "command": "x", "args": ["a"] }
        |  }
        |}""".stripMargin
    val out = mergeJson(Some(existing), name, argv, Seq("timeout" -> "60000"))
    assert(out.contains("\"other\""), "kept other server")
    assert(out.contains("\"someOtherKey\""), "kept unrelated root key")
    assert(out.contains("\"scala-semantic\""), "added our server")
    assert(out.contains("\"timeout\": 60000"), "emitted extra field")
  }

  test("json: replaces our own entry without touching siblings") {
    val existing =
      """{
        |  "mcpServers": {
        |    "scala-semantic": { "command": "OLD", "args": [] },
        |    "keep": { "command": "k", "args": [] }
        |  }
        |}""".stripMargin
    val out = mergeJson(Some(existing), name, argv, Nil)
    assert(!out.contains("OLD"), "old value replaced")
    assert(out.contains("\"keep\""), "kept sibling")
    assert(out.contains("\"/bin/launch.sh\""), "new command present")
  }

  test("json: adds mcpServers to a root object that lacks it") {
    val out = mergeJson(Some("""{ "foo": 1 }"""), name, argv, Nil)
    assert(out.contains("\"foo\""), "kept foo")
    assert(out.contains("\"mcpServers\""), "added mcpServers")
  }

  test("json: not-an-object content falls back to a fresh document") {
    val out = mergeJson(Some("garbage"), name, argv, Nil)
    assertEquals(out, mergeJson(None, name, argv, Nil))
  }

  test("json: idempotent (fresh, with-extra, and with-other-server)") {
    assertIdempotent("json/fresh", e => mergeJson(e, name, argv, Nil), None)
    assertIdempotent("json/extra", e => mergeJson(e, name, argv, Seq("timeout" -> "60000")), None)
    val withOther =
      """{
        |  "someOtherKey": true,
        |  "mcpServers": { "other": { "command": "x", "args": ["a"] } }
        |}""".stripMargin
    assertIdempotent("json/other", e => mergeJson(e, name, argv, Nil), Some(withOther))
    val out = mergeJson(Some(withOther), name, argv, Nil)
    assert(out.contains("\"other\"") && out.contains("\"someOtherKey\""))
  }

  // --- TOML -------------------------------------------------------------------------------------

  test("toml: replaces our table and keeps other tables") {
    val existing =
      """[mcp_servers.other]
        |command = "x"
        |args = ["a"]
        |
        |[mcp_servers.scala-semantic]
        |command = "OLD"
        |args = []
        |
        |[other_table]
        |foo = 1""".stripMargin
    val out = mergeToml(Some(existing), name, argv)
    assert(out.contains("[mcp_servers.other]"), "kept other server")
    assert(out.contains("[other_table]") && out.contains("foo = 1"), "kept unrelated table")
    assert(!out.contains("OLD"), "replaced old value")
    assert(out.contains("\"/bin/launch.sh\""), "new command present")
  }

  test("toml: appends our table when absent") {
    val existing = "[mcp_servers.other]\ncommand = \"x\"\nargs = []\n"
    val out = mergeToml(Some(existing), name, argv)
    assert(out.contains("[mcp_servers.other]"))
    assert(out.contains("[mcp_servers.scala-semantic]"))
  }

  test("toml: ends with exactly one trailing newline") {
    val out = mergeToml(None, name, argv)
    assert(out.endsWith("\n"))
    assert(!out.endsWith("\n\n"))
  }

  test("toml: idempotent (fresh and with-other-server)") {
    assertIdempotent("toml/fresh", e => mergeToml(e, name, argv), None)
    val withOther =
      """[mcp_servers.other]
        |command = "x"
        |args = ["a"]
        |
        |[other_table]
        |foo = 1""".stripMargin
    assertIdempotent("toml/other", e => mergeToml(e, name, argv), Some(withOther))
    val out = mergeToml(Some(withOther), name, argv)
    assert(out.contains("[mcp_servers.other]") && out.contains("[other_table]"))
  }

  // --- YAML -------------------------------------------------------------------------------------

  test("yaml: replaces our list item and keeps other items and blocks") {
    val existing =
      """name: My
        |mcpServers:
        |  - name: "other"
        |    command: "x"
        |  - name: "scala-semantic"
        |    command: "OLD"
        |    connectionTimeout: 1
        |models:
        |  - name: gpt""".stripMargin
    val out = mergeYaml(Some(existing), name, argv)
    assert(out.contains("""- name: "other""""), "kept other item")
    assert(out.contains("models:") && out.contains("- name: gpt"), "kept unrelated block")
    assert(!out.contains("OLD"), "replaced old value")
    assert(out.contains("\"/bin/launch.sh\""), "new command present")
  }

  test("yaml: appends our item when mcpServers exists without it") {
    val existing = "name: My\nmcpServers:\n  - name: \"other\"\n    command: \"x\"\n"
    val out = mergeYaml(Some(existing), name, argv)
    assert(out.contains("""- name: "other""""))
    assert(out.contains("""- name: "scala-semantic""""))
  }

  test("yaml: re-merging does not duplicate the item's own args sequence (regression)") {
    // The args list entries (`- "/proj"`) are deeper `- ` lines; they must NOT be mistaken for the
    // next server item, which previously duplicated args + connectionTimeout on re-merge.
    val once = mergeYaml(None, name, argv)
    val twice = mergeYaml(Some(once), name, argv)
    assertEquals(twice, once)
    // exactly one occurrence of the args anchor and the connection timeout
    assertEquals(twice.sliding("connectionTimeout".length).count(_ == "connectionTimeout"), 1)
    assertEquals(twice.split("\n").count(_.trim == "- \"/proj\""), 1)
  }

  test("yaml: idempotent (fresh and with-other-server)") {
    assertIdempotent("yaml/fresh", e => mergeYaml(e, name, argv), None)
    val withOther =
      """name: My
        |mcpServers:
        |  - name: "other"
        |    command: "x"
        |models:
        |  - name: gpt""".stripMargin
    assertIdempotent("yaml/other", e => mergeYaml(e, name, argv), Some(withOther))
    val out = mergeYaml(Some(withOther), name, argv)
    assert(out.contains("""- name: "other"""") && out.contains("models:"))
  }

  test("writeRulesAndSteer: writes SCALA_SEMANTIC_RULES.md and CLAUDE.md for claude") {
    val tempDir = java.nio.file.Files.createTempDirectory("scalasemantic-test-").toFile
    val log = new sbt.Logger {
      def trace(t: => Throwable): Unit = ()
      def success(message: => String): Unit = ()
      def log(level: sbt.Level.Value, message: => String): Unit = ()
    }
    try {
      ScalaSemanticConfigMerger.writeRulesAndSteer("claude", tempDir, log)
      val rulesFile = new sbt.File(tempDir, "SCALA_SEMANTIC_RULES.md")
      val claudeFile = new sbt.File(tempDir, "CLAUDE.md")

      assert(rulesFile.exists())
      assert(claudeFile.exists())

      val claudeContent = sbt.IO.read(claudeFile)
      assert(claudeContent.contains("SCALA_SEMANTIC_RULES.md"))
    } finally {
      sbt.IO.delete(tempDir)
    }
  }

  test("writeRulesAndSteer: migrates legacy SCALA_CODE_RULES.md in AGENTS.md for gemini") {
    val tempDir = java.nio.file.Files.createTempDirectory("scalasemantic-test-").toFile
    val log = new sbt.Logger {
      def trace(t: => Throwable): Unit = ()
      def success(message: => String): Unit = ()
      def log(level: sbt.Level.Value, message: => String): Unit = ()
    }
    try {
      val agentsFile = new sbt.File(tempDir, "AGENTS.md")
      sbt.IO.write(agentsFile, "<INSTRUCTIONS>\n@SCALA_CODE_RULES.md\n</INSTRUCTIONS>")

      ScalaSemanticConfigMerger.writeRulesAndSteer("gemini", tempDir, log)

      val rulesFile = new sbt.File(tempDir, "SCALA_SEMANTIC_RULES.md")
      assert(rulesFile.exists())

      val updatedAgentsContent = sbt.IO.read(agentsFile)
      assert(updatedAgentsContent.contains("@SCALA_SEMANTIC_RULES.md"))
      assert(!updatedAgentsContent.contains("SCALA_CODE_RULES.md"))
    } finally {
      sbt.IO.delete(tempDir)
    }
  }

  test("writeRulesAndSteer: migrates legacy SCALA_CODE_RULES.md in AGENTS.md for antigravity") {
    val tempDir = java.nio.file.Files.createTempDirectory("scalasemantic-test-").toFile
    val log = new sbt.Logger {
      def trace(t: => Throwable): Unit = ()
      def success(message: => String): Unit = ()
      def log(level: sbt.Level.Value, message: => String): Unit = ()
    }
    try {
      val agentsFile = new sbt.File(tempDir, "AGENTS.md")
      sbt.IO.write(agentsFile, "<INSTRUCTIONS>\n@SCALA_CODE_RULES.md\n</INSTRUCTIONS>")

      ScalaSemanticConfigMerger.writeRulesAndSteer("antigravity", tempDir, log)

      val rulesFile = new sbt.File(tempDir, "SCALA_SEMANTIC_RULES.md")
      assert(rulesFile.exists())

      val updatedAgentsContent = sbt.IO.read(agentsFile)
      assert(updatedAgentsContent.contains("@SCALA_SEMANTIC_RULES.md"))
      assert(!updatedAgentsContent.contains("SCALA_CODE_RULES.md"))
    } finally {
      sbt.IO.delete(tempDir)
    }
  }

  import org.scalacheck.Gen
  import org.scalacheck.Prop.forAll

  private val genAlphaStr: Gen[String] = Gen.nonEmptyListOf(Gen.alphaChar).map(_.mkString)

  private val genJsonPrimitive: Gen[String] = Gen.oneOf(
    Gen.chooseNum(1, 100).map(_.toString),
    Gen.oneOf("true", "false"),
    genAlphaStr.map(s => s""""$s"""")
  )

  private val genUnrelatedJsonPair: Gen[(String, String)] = for {
    key <- genAlphaStr.suchThat(_ != "mcpServers")
    value <- genJsonPrimitive
  } yield (s""""$key"""", value)

  private val genServerEntry: Gen[String] = for {
    command <- genAlphaStr
    args <- Gen.listOf(genAlphaStr)
    argsStr = args.map(a => s""""$a"""").mkString("[", ", ", "]")
  } yield s"""{\n      "command": "${command}",\n      "args": $argsStr\n    }"""

  private val genJsonMcpServers: Gen[String] = for {
    servers <- Gen.listOf(for {
      name <- genAlphaStr
      entry <- genServerEntry
    } yield s""""$name": $entry""")
  } yield s"""{\n    ${servers.mkString(",\n    ")}\n  }"""

  private val genJsonConfig: Gen[String] = for {
    pairs <- Gen.listOf(genUnrelatedJsonPair)
    includeMcpServers <- Gen.oneOf(true, false)
    mcpServersBlock <- if (includeMcpServers) genJsonMcpServers.map(s => s""""mcpServers": $s""") else Gen.const("")
  } yield {
    val allPairs = pairs.map { case (k, v) => s"$k: $v" } ++ (if (mcpServersBlock.nonEmpty) Seq(mcpServersBlock) else Seq.empty)
    s"""{\n  ${scala.util.Random.shuffle(allPairs).mkString(",\n  ")}\n}"""
  }

  private val genExtraFields: Gen[Seq[(String, String)]] = Gen.listOf(for {
    key <- genAlphaStr
    value <- genJsonPrimitive
  } yield (key, value))

  private val genTomlPair: Gen[String] = for {
    key <- genAlphaStr
    value <- genJsonPrimitive
  } yield s"$key = $value"

  private val genTomlTable: Gen[String] = for {
    tableName <- genAlphaStr.suchThat(name => name != "mcp_servers" && name != "mcp-servers")
    pairs <- Gen.listOf(genTomlPair)
  } yield s"[$tableName]\n${pairs.mkString("\n")}"

  private val genTomlMcpServers: Gen[String] = for {
    servers <- Gen.listOf(for {
      serverName <- genAlphaStr
      command <- genAlphaStr
      args <- Gen.listOf(genAlphaStr)
      argsStr = args.map(a => s""""$a"""").mkString("[", ", ", "]")
    } yield s"""[mcp_servers.$serverName]\ncommand = "$command"\nargs = $argsStr""")
  } yield servers.mkString("\n\n")

  private val genTomlConfig: Gen[String] = for {
    tables <- Gen.listOf(genTomlTable)
    includeMcp <- Gen.oneOf(true, false)
    mcpBlock <- if (includeMcp) genTomlMcpServers else Gen.const("")
  } yield {
    val allBlocks = tables ++ (if (mcpBlock.nonEmpty) Seq(mcpBlock) else Seq.empty)
    scala.util.Random.shuffle(allBlocks).mkString("\n\n")
  }

  private val genYamlPair: Gen[String] = for {
    key <- genAlphaStr.suchThat(_ != "mcpServers")
    value <- genJsonPrimitive
  } yield s"$key: $value"

  private val genYamlMcpServerItem: Gen[String] = for {
    name <- genAlphaStr
    command <- genAlphaStr
    args <- Gen.listOf(genAlphaStr)
    argsLines = args.map(a => s"""      - "$a"""").mkString("\n")
    argsStr = if (args.isEmpty) "" else s"\n    args:\n$argsLines"
  } yield s"""  - name: "$name"\n    command: "$command"$argsStr"""

  private val genYamlMcpServers: Gen[String] = for {
    items <- Gen.listOf(genYamlMcpServerItem)
  } yield if (items.isEmpty) "" else s"mcpServers:\n${items.mkString("\n")}"

  private val genYamlConfig: Gen[String] = for {
    pairs <- Gen.listOf(genYamlPair)
    includeMcp <- Gen.oneOf(true, false)
    mcpBlock <- if (includeMcp) genYamlMcpServers else Gen.const("")
  } yield {
    val allParts = pairs ++ (if (mcpBlock.nonEmpty) Seq(mcpBlock) else Seq.empty)
    scala.util.Random.shuffle(allParts).mkString("\n")
  }

  property("json merge: idempotent over generated config documents and extra fields") {
    forAll(genJsonConfig, genExtraFields) { (existingJson, extraFields) =>
      assertIdempotent("json/pb", e => mergeJson(e, name, argv, extraFields), Some(existingJson))
      true
    }
  }

  property("toml merge: idempotent over generated config documents") {
    forAll(genTomlConfig) { existingToml =>
      assertIdempotent("toml/pb", e => mergeToml(e, name, argv), Some(existingToml))
      true
    }
  }

  property("yaml merge: idempotent over generated config documents") {
    forAll(genYamlConfig) { existingYaml =>
      assertIdempotent("yaml/pb", e => mergeYaml(e, name, argv), Some(existingYaml))
      true
    }
  }
}
