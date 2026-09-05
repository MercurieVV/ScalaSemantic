package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files

/** ADR-0004: when the launch directory is not a Scala project the server stays connectable and
  * explains itself per tool call, instead of exiting and showing as a failed server in every
  * non-Scala repository on the machine.
  */
class UnresolvedRootSuite extends munit.FunSuite:

  test("tools are wrapped with the error, except the workspace-root escape hatches") {
    val scratch = Files.createTempDirectory("unresolved-root")
    try
      val message = "could not detect a Scala project root at or above '/tmp/x'"
      val tools = Mcp.unresolvedRootTools(scratch, message)
      assert(tools.nonEmpty, "expected the full tool list even with an unresolved root")
      val names = tools.map(_.name).toSet
      assert(names.contains("find_symbol"), s"tool list looks wrong: $names")
      assert(names.contains("set_workspace_root"), s"escape hatch missing: $names")

      val findSymbol = tools.find(_.name == "find_symbol").get
      val result = ujson.write(findSymbol.run(ujson.Obj("name" -> "Anything")))
      assert(
        result.contains("could not detect a Scala project root"),
        s"expected the discovery error from a wrapped tool, got:\n$result"
      )

      val getRoot = tools.find(_.name == "get_workspace_root").get
      val rootResult = ujson.write(getRoot.run(ujson.Obj()))
      assert(
        !rootResult.contains("could not detect a Scala project root"),
        s"get_workspace_root must not be wrapped, got:\n$rootResult"
      )
    finally Files.walk(scratch).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }
