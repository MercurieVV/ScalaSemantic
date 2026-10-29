package com.github.mercurievv.scalasemantic.mcp

/** `Mcp.callOnce`: the one-shot entry a shell hook uses to run a single tool without an MCP
  * session. PC-free — `find_symbol` needs no classpath, so this suite never depends on a
  * presentation-compiler backend.
  */
class CallOnceSuite extends munit.FunSuite:

  test("callOnce runs one tool against the root's index and returns its JSON") {
    val res = Mcp.callOnce(".", None, "find_symbol", ujson.Obj("name" -> "Analyzer"))
    val json = res.fold(err => fail(err), identity)
    assert(json.obj.nonEmpty, json.toString)
  }

  test("call literal_tokens answers one-shot, without a classpath") {
    val args = ujson.Obj(
      "files" -> ujson.Arr(
        ujson.Obj("uri" -> "A.scala", "source" -> "package p\nobject O:\n  val x = 42\n")
      )
    )
    val json = Mcp
      .callOnce(".", None, "literal_tokens", args)
      .fold(err => fail(s"call failed: $err"), identity)
    assertEquals(json("files")(0)("literals")(0)("value").str, "42")
  }

  test("call contract_clauses answers one-shot, without a classpath") {
    val args = ujson.Obj(
      "files" -> ujson.Arr(
        ujson.Obj(
          "uri" -> "K.scala",
          "source" -> "package p\nobject K:\n  def f(x: Int): Int = { require(x > 0); x }\n"
        )
      )
    )
    val json = Mcp
      .callOnce(".", None, "contract_clauses", args)
      .fold(err => fail(s"call failed: $err"), identity)
    assertEquals(json("files")(0)("defs")(0)("key").str, "K.f")
  }

  test("callOnce names an unknown tool instead of failing silently") {
    val res = Mcp.callOnce(".", None, "no_such_tool", ujson.Obj())
    assert(res.isLeft, res.toString)
    assert(res.swap.exists(_.contains("no_such_tool")), res.toString)
  }

  test("callOnce returns a Left when a tool throws on malformed arguments") {
    // `resolve_references` parses `ranges` with `r.arr`/`_.num`; a shell hook must get a message
    // and exit 1, not a stack trace out of `main`.
    val res = Mcp.callOnce(
      ".",
      None,
      "resolve_references",
      ujson.Obj(
        "files" -> ujson.Arr(
          ujson.Obj("uri" -> "X.scala", "ranges" -> ujson.Arr(ujson.Num(5)))
        )
      )
    )
    assert(res.isLeft, res.toString)
    assert(res.swap.exists(_.contains("resolve_references")), res.toString)
  }
