package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Paths

/** The explicit classpath spec (CLI argument / `SCALASEMANTIC_CLASSPATH`) belongs to the root the
  * server started on. After `set_workspace_root` to another project, that project must discover its
  * own classpath metadata instead of inheriting the first project's.
  */
class ClasspathSpecForSuite extends munit.FunSuite:

  private val start = Paths.get("/work/app").nn
  private val spec = Some("/work/app/.scala-semantic/classpath-mill.json")

  test("the startup root keeps its explicit classpath spec"):
    assertEquals(Mcp.classpathSpecFor(start, spec, start), spec)

  test("a non-normalized spelling of the startup root still counts as the startup root"):
    assertEquals(Mcp.classpathSpecFor(start, spec, Paths.get("/work/app/./sub/..").nn), spec)

  test("another root drops the explicit spec, so it discovers its own metadata"):
    assertEquals(Mcp.classpathSpecFor(start, spec, Paths.get("/work/other-lib").nn), None)

  test("a sibling whose path merely starts with the startup root is another root"):
    assertEquals(Mcp.classpathSpecFor(start, spec, Paths.get("/work/app-two").nn), None)

  test("a nested subproject is another root too"):
    assertEquals(Mcp.classpathSpecFor(start, spec, Paths.get("/work/app/modules/core").nn), None)

  test("no explicit spec means discovery everywhere"):
    assertEquals(Mcp.classpathSpecFor(start, None, start), None)
