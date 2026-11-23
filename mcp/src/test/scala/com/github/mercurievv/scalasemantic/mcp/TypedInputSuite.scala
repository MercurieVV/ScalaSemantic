package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.JOps.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import io.circe.Json

import java.nio.file.Path
import java.nio.file.Paths

/** Typed tool arguments (ADR 0006, stage B), piloted on `find_symbol`: the `inputSchema` is derived
  * from the argument type, a bad request fails with one message naming the field, and unknown
  * arguments are ignored (and optionally logged). Uses this suite's own tool instances.
  */
class TypedInputSuite extends munit.FunSuite:

  private val root: Path = Paths.get(".")
  private val tool: Tool = Mcp
    .toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)
    .find(_.name == "find_symbol")
    .getOrElse(fail("no find_symbol"))

  private def run(args: (String, J.V)*): Json = tool.run(J.obj(args*))

  test("inputSchema is derived from the argument type: required = non-Option fields"):
    val s = tool.inputSchema
    assertEquals(s("type").str, "object")
    assertEquals(s("required").arr.map(_.str).toSet, Set("query"))
    assertEquals(
      s("properties").obj.keySet.toSet,
      Set("query", "limit", "exact", "kind", "pathFilter", "metrics")
    )
    assertEquals(s("properties")("limit")("type").str, "integer")
    assertEquals(s("properties")("exact")("type").str, "boolean")
    assert(s("properties")("query")("description").str.nonEmpty)

  test("a typed call behaves as before: query, limit, exact"):
    val res = run("query" -> "Analyzer", "limit" -> 3)
    assertEquals(res("query").str, "Analyzer")
    assert(res("symbols").arr.size <= 3 && res("symbols").arr.nonEmpty)
    val exact = run("query" -> "FindUsagesResult", "exact" -> true)
    assert(exact("symbols").arr.forall(_("name").str == "FindUsagesResult"))

  test("a missing required argument names the field"):
    val e = intercept[RuntimeException](run("limit" -> 3))
    assert(e.getMessage.contains("find_symbol: invalid arguments"), e.getMessage)
    assert(e.getMessage.contains("query"), e.getMessage)

  test("a mistyped argument names the field"):
    val e = intercept[RuntimeException](run("query" -> "A", "limit" -> "many"))
    assert(e.getMessage.contains("`limit`"), e.getMessage)

  test("a non-positive limit is still rejected by validation"):
    val e = intercept[RuntimeException](run("query" -> "A", "limit" -> 0))
    assert(e.getMessage.toLowerCase.contains("limit"), e.getMessage)

  test("unknown arguments are ignored, and shared shaping arguments are not unknown"):
    val res = run("query" -> "Analyzer", "limit" -> 2, "bogus" -> true)
    assertEquals(res("query").str, "Analyzer")

  test("unknown arguments are logged only when the warning is switched on"):
    val key = "scalasemantic.warnUnknownArgs"
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val previous = Mcp.log.getAndSet(line => { val _ = seen.add(line) })
    try
      val _ = run("query" -> "Analyzer", "limit" -> 1, "bogus" -> 1)
      assert(seen.isEmpty, "off by default")
      val _ = System.setProperty(key, "true")
      val _ = run("query" -> "Analyzer", "limit" -> 1, "bogus" -> 1, "fields" -> J.arr())
      assertEquals(seen.size, 1)
      val line = Option(seen.peek()).getOrElse("")
      assert(line.contains("find_symbol") && line.contains("bogus"), line)
      assert(!line.contains("fields"), "shared shaping args are never reported")
    finally
      val _ = System.clearProperty(key)
      Mcp.log.set(previous)

  private val allTools: List[Tool] = Mcp.toolsFor(Analyzer(SemanticIndex.fromProject(".")), root)

  test("the symbol-lookup tools derive their inputSchema from typed arguments"):
    val expected: Map[String, (Set[String], Set[String])] = Map(
      "find_usages" -> (Set("symbol") ->
        Set("symbol", "limit", "offset", "pathFilter", "include", "contextLines", "related")),
      "method_signature" -> (Set("symbol") -> Set("symbol", "detailed", "uri", "source")),
      "class_hierarchy" ->
        (Set("symbol") -> Set("symbol", "detailed", "pathFilter", "include", "metrics")),
      "find_overloads" -> (Set("symbol") -> Set("symbol")),
      "members" -> (Set("symbol") -> Set("symbol", "detailed", "pathFilter", "include")),
      "resolve_implicits" -> (Set("type") -> Set("type"))
    )
    expected.foreach: (name, shape) =>
      val s = allTools.find(_.name == name).getOrElse(fail(s"no $name")).inputSchema
      assertEquals(s("required").arr.map(_.str).toSet, shape._1, name)
      assertEquals(s("properties").obj.keySet.toSet, shape._2, name)
      assert(s("properties").obj.values.forall(_("description").str.nonEmpty), name)

  test("array arguments are declared as arrays of strings"):
    val s = allTools.find(_.name == "find_usages").getOrElse(fail("no find_usages")).inputSchema
    assertEquals(s("properties")("include")("type").str, "array")
    assertEquals(s("properties")("include")("items")("type").str, "string")

  test("symbol-lookup tools name the missing field; paging and sections still work"):
    def call(name: String, args: (String, J.V)*): Json =
      allTools.find(_.name == name).getOrElse(fail(s"no $name")).run(J.obj(args*))
    val e = intercept[RuntimeException](call("resolve_implicits"))
    assert(e.getMessage.contains("resolve_implicits: invalid arguments"), e.getMessage)
    assert(e.getMessage.contains("type"), e.getMessage)
    val usages = call(
      "find_usages",
      "symbol" -> "com/github/mercurievv/scalasemantic/mcp/McpToolsSupport.loc().",
      "limit" -> 1,
      "include" -> J.arr("references")
    )
    assert(!usages.obj.contains("definitions"), "include trims sections")
    assert(usages("references").arr.size <= 1)

  test("the graph, position and text-search tools derive their inputSchema too"):
    val expected: Map[String, (Set[String], Set[String])] = Map(
      "call_path" -> (Set("from", "to") -> Set("from", "to", "detailed")),
      "method_call_hierarchy" -> (Set("symbol") -> Set("symbol", "direction", "depth")),
      "trace_implicit_chain" -> (Set("type") -> Set("type", "appliedType")),
      "type_at_position" ->
        (Set("uri", "line", "character") -> Set("uri", "line", "character", "source")),
      "search_text" ->
        (Set("query") -> Set("query", "regex", "caseSensitive", "pathFilter", "limit"))
    )
    expected.foreach: (name, shape) =>
      val s = allTools.find(_.name == name).getOrElse(fail(s"no $name")).inputSchema
      assertEquals(s("required").arr.map(_.str).toSet, shape._1, name)
      assertEquals(s("properties").obj.keySet.toSet, shape._2, name)
      assert(s("properties").obj.values.forall(_("description").str.nonEmpty), name)

  test("type_at_position and search_text still answer, and reject a missing required argument"):
    def call(name: String, args: (String, J.V)*): Json =
      allTools.find(_.name == name).getOrElse(fail(s"no $name")).run(J.obj(args*))
    val hits = call("search_text", "query" -> "TypedTool", "limit" -> 2)
    assert(hits("hits").arr.size <= 2 && hits("hits").arr.nonEmpty)
    val miss = intercept[RuntimeException](call("type_at_position", "uri" -> "A.scala"))
    assert(miss.getMessage.contains("type_at_position: invalid arguments"), miss.getMessage)
    assert(miss.getMessage.contains("line"), miss.getMessage)

  test("the source tools derive their inputSchema, sharing one wording for the view options"):
    val view = Set("format", "annotationsOnly", "symbols", "docs", "detail", "sentinel")
    val expected: Map[String, (Set[String], Set[String])] = Map(
      "annotated_source" -> (Set("uri") -> (view + "uri" + "write" + "baseHash")),
      "symbol_source" -> (Set("symbol") -> (view + "symbol")),
      "source_around_position" ->
        (Set("file", "line", "column") -> (view + "file" + "line" + "column")),
      "source_ranges" -> (Set("query") -> Set("query", "annotationsOnly", "docs", "detail")),
      "document_outline" ->
        (Set("uri") ->
          Set("uri", "source", "query", "symbol", "includeParents", "maxDepth", "kind"))
    )
    def schema(name: String) =
      allTools.find(_.name == name).getOrElse(fail(s"no $name")).inputSchema
    expected.foreach: (name, shape) =>
      val s = schema(name)
      assertEquals(s("required").arr.map(_.str).toSet, shape._1, name)
      assertEquals(s("properties").obj.keySet.toSet, shape._2, name)
      assert(s("properties").obj.values.forall(_("description").str.nonEmpty), name)
    List("sentinel", "format", "symbols").foreach: opt =>
      val texts = List("annotated_source", "symbol_source", "source_around_position")
        .map(n => schema(n)("properties")(opt)("description").str)
      assertEquals(texts.distinct.size, 1, s"$opt wording must not drift between tools")

  test("source tools reject a missing required argument by name, and still render"):
    def call(name: String, args: (String, J.V)*): Json =
      allTools.find(_.name == name).getOrElse(fail(s"no $name")).run(J.obj(args*))
    val e = intercept[RuntimeException](call("source_around_position", "file" -> "A.scala"))
    assert(e.getMessage.contains("source_around_position: invalid arguments"), e.getMessage)
    assert(e.getMessage.contains("line"), e.getMessage)
    val src = call(
      "annotated_source",
      "uri" -> "wire/src/main/scala/com/github/mercurievv/scalasemantic/wire/api/ToolArgs.scala",
      "format" -> "plain"
    )
    assert(src("source").str.contains("final case class FindSymbolArgs"))
    val outline = call(
      "document_outline",
      "uri" -> "wire/src/main/scala/com/github/mercurievv/scalasemantic/wire/api/ToolArgs.scala",
      "query" -> "Args",
      "maxDepth" -> 1
    )
    assert(outline("filtered").bool && outline("outline").arr.nonEmpty)

  test("the plan and analysis tools derive their inputSchema from typed arguments"):
    val expected: Map[String, (Set[String], Set[String])] = Map(
      "rename_plan" -> (Set("symbol", "newName") -> Set("symbol", "newName")),
      "batch_rename_plan" -> (Set("renames") -> Set("renames")),
      "move_plan" -> (Set("symbol", "newOwner") -> Set("symbol", "newOwner")),
      "extract_method_plan" ->
        (Set("uri", "startLine", "startCharacter", "endLine", "endCharacter") ->
          Set(
            "uri",
            "startLine",
            "startCharacter",
            "endLine",
            "endCharacter",
            "methodName",
            "source"
          )),
      "structure" ->
        (Set.empty[String] ->
          Set("sort", "dimension", "pathFilter", "limit", "detailed", "graph")),
      "value_flow" ->
        (Set.empty[String] ->
          Set("symbol", "file", "line", "column", "maxDepth", "stopOnTypeWidening")),
      "smart_code_duplications" ->
        (Set.empty[String] -> Set("minSize", "pathFilter", "showSource")),
      "hole_context" ->
        (Set("uri", "line", "character") -> Set("uri", "line", "character", "source"))
    )
    expected.foreach: (name, shape) =>
      val s = allTools.find(_.name == name).getOrElse(fail(s"no $name")).inputSchema
      val required = s.obj.get("required").map(_.arr.map(_.str).toSet).getOrElse(Set.empty)
      assertEquals(required, shape._1, name)
      assertEquals(s("properties").obj.keySet.toSet, shape._2, name)
      assert(s("properties").obj.values.forall(_("description").str.nonEmpty), name)
    val items = allTools
      .find(_.name == "batch_rename_plan")
      .getOrElse(fail("no batch_rename_plan"))
      .inputSchema("properties")("renames")("items")
    assertEquals(items("required").arr.map(_.str).toSet, Set("symbol", "newName"))

  test("plan tools still plan, and name the missing field when called wrongly"):
    def call(name: String, args: (String, J.V)*): Json =
      allTools.find(_.name == name).getOrElse(fail(s"no $name")).run(J.obj(args*))
    val plan = call(
      "rename_plan",
      "symbol" -> "com/github/mercurievv/scalasemantic/wire/api/NotFoundResult#",
      "newName" -> "Gone"
    )
    assert(plan("editCount").num > 0)
    val batch = call(
      "batch_rename_plan",
      "renames" -> J.arr(
        J.obj(
          "symbol" -> "com/github/mercurievv/scalasemantic/wire/api/NotFoundResult#",
          "newName" -> "Gone"
        )
      )
    )
    assertEquals(batch("perSymbol").arr.size, 1)
    val e = intercept[RuntimeException](call("move_plan", "symbol" -> "x/Y#"))
    assert(e.getMessage.contains("move_plan: invalid arguments"), e.getMessage)
    assert(e.getMessage.contains("newOwner"), e.getMessage)
    assertEquals(call("value_flow")("found").bool, false)

  test("the multi-file tools declare files[] items, and reject a malformed range by name"):
    List("literal_tokens", "resolve_references", "contract_clauses").foreach: name =>
      val s = allTools.find(_.name == name).getOrElse(fail(s"no $name")).inputSchema
      assertEquals(s("required").arr.map(_.str).toSet, Set("files"), name)
      val item = s("properties")("files")("items")
      assertEquals(item("required").arr.map(_.str).toSet, Set("uri"), name)
      assert(item("properties").obj.contains("source"), name)
    val ranged = allTools.find(_.name == "literal_tokens").getOrElse(fail("no literal_tokens"))
    assertEquals(
      ranged.inputSchema("properties")("files")("items")("properties")("ranges")("type").str,
      "array"
    )
    val bad = intercept[RuntimeException](
      ranged.run(
        J.obj(
          "files" -> J.arr(J.obj("uri" -> "X.scala", "ranges" -> J.arr(J.num(5))))
        )
      )
    )
    assert(bad.getMessage.contains("literal_tokens: invalid arguments"), bad.getMessage)
    val ok = ranged.run(
      J.obj(
        "files" -> J.arr(
          J.obj(
            "uri" -> "A.scala",
            "source" -> "object A:\n  val x = 42\n",
            "ranges" -> J.arr(J.arr(0, 2))
          )
        )
      )
    )
    assertEquals(ok("files")(0)("literals")(0)("value").str, "42")

  test("the workspace tools and output_schema derive their inputSchema too"):
    val expected: Map[String, (Set[String], Set[String])] = Map(
      "set_workspace_root" -> (Set("path") -> Set("path")),
      "refresh_workspace" -> (Set.empty[String] -> Set("path", "compile")),
      "output_schema" -> (Set.empty[String] -> Set("tool"))
    )
    expected.foreach: (name, shape) =>
      val s = allTools.find(_.name == name).getOrElse(fail(s"no $name")).inputSchema
      val required = s.obj.get("required").map(_.arr.map(_.str).toSet).getOrElse(Set.empty)
      assertEquals(required, shape._1, name)
      assertEquals(s("properties").obj.keySet.toSet, shape._2, name)
    val e = intercept[RuntimeException](
      allTools.find(_.name == "set_workspace_root").getOrElse(fail("none")).run(J.obj())
    )
    assert(e.getMessage.contains("set_workspace_root: invalid arguments"), e.getMessage)
    val one = allTools
      .find(_.name == "output_schema")
      .getOrElse(fail("none"))
      .run(J.obj("tool" -> "find_symbol"))
    assert(one.obj.nonEmpty)

  test("J writes whole doubles as integers and keeps nesting and order intact"):
    val j = J.obj(
      "a" -> 2,
      "b" -> 2.5,
      "c" -> J.arr(true, Json.Null, "x"),
      "d" -> J.obj(),
      "e" -> 3.0
    )
    assertEquals(j.noSpaces, """{"a":2,"b":2.5,"c":[true,null,"x"],"d":{},"e":3}""")
