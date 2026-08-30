package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files
import java.nio.file.Path

/** `refresh_workspace(compile = true)`: auto-detects the project's build (mill/sbt/scala-cli) and
  * runs its compile task itself, so a session that cannot shell out (e.g. Claude Code plan mode)
  * still has a way to populate a never-compiled or stale SemanticDB index (#296).
  */
class RefreshWorkspaceCompileSuite extends munit.FunSuite:

  private def tempProject(name: String): Path =
    val root = Files.createTempDirectory(name).nn
    root.toFile.nn.deleteOnExit()
    root

  test("detectCompileCommand is None for a directory with no recognized build") {
    val root = tempProject("ss-compile-none")
    assertEquals(Mcp.detectCompileCommand(root), None)
  }

  test("detectCompileCommand picks mill when build.mill is present") {
    val root = tempProject("ss-compile-mill")
    Files.writeString(root.resolve("build.mill"), "")
    assertEquals(Mcp.detectCompileCommand(root), Some(Seq("mill", "__.compile")))
  }

  test("detectCompileCommand prefers an executable ./mill wrapper when one exists") {
    val root = tempProject("ss-compile-mill-wrapper")
    Files.writeString(root.resolve("build.mill"), "")
    val wrapper = root.resolve("mill")
    Files.writeString(wrapper, "#!/bin/sh\nexit 0\n")
    val _ = wrapper.toFile.nn.setExecutable(true)
    assertEquals(Mcp.detectCompileCommand(root), Some(Seq("./mill", "__.compile")))
  }

  test("detectCompileCommand picks sbt when a .sbt file is present") {
    val root = tempProject("ss-compile-sbt")
    Files.writeString(root.resolve("build.sbt"), "")
    assertEquals(Mcp.detectCompileCommand(root), Some(Seq("sbt", "compile", "Test/compile")))
  }

  test("detectCompileCommand picks scala-cli when only project.scala is present") {
    val root = tempProject("ss-compile-scalacli")
    Files.writeString(root.resolve("project.scala"), "//> using scala \"3.8.4\"\n")
    assertEquals(
      Mcp.detectCompileCommand(root),
      Some(Seq("scala-cli", "compile", ".", "--test"))
    )
  }

  test("runCompile reports a clear error when no build is detected, without throwing") {
    val root = tempProject("ss-compile-run-none")
    val result = Mcp.runCompile(root, _ => ())
    result match
      case Left(msg)  => assert(msg.contains("could not detect a build"), msg)
      case Right(out) => fail(s"expected Left, got Right($out)")
  }

  test("refresh_workspace advertises the compile parameter in its schema") {
    val tool = Mcp.refreshWorkspaceTool(_ => ())
    val props = tool.inputSchema("properties")
    assert(props.obj.contains("compile"), props.toString)
    assertEquals(props("compile")("type").str, "boolean")
  }

  test("refresh_workspace(compile=true) surfaces the compile failure without crashing the tool") {
    val root = tempProject("ss-compile-tool-none")
    val tool = Mcp.refreshWorkspaceTool(_ => ())
    val result = tool.run(
      ujson.Obj("path" -> root.toString, "compile" -> true)
    )
    assert(result("compileError").str.contains("could not detect a build"), result.toString)
    // the rebuild still ran despite the compile step failing
    assert(result.obj.contains("semanticdbFileCount"), result.toString)
  }
