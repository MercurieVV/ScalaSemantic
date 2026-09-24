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

  test("callOnce names an unknown tool instead of failing silently") {
    val res = Mcp.callOnce(".", None, "no_such_tool", ujson.Obj())
    assert(res.isLeft, res.toString)
    assert(res.swap.exists(_.contains("no_such_tool")), res.toString)
  }
