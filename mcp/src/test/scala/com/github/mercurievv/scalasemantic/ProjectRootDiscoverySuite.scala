package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

class ProjectRootDiscoverySuite extends munit.FunSuite:

  private def tempDir(): Path = Files.createTempDirectory("project-root-discovery-suite")

  test("finds a marker in cwd itself"):
    val root = tempDir()
    Files.createFile(root.resolve("build.sbt"))
    assertEquals(ProjectRootDiscovery.find(root), Some(root.toAbsolutePath.normalize()))

  test("walks up to an ancestor marker"):
    val root = tempDir()
    Files.createFile(root.resolve("build.mill"))
    val nested = Files.createDirectories(root.resolve("sub/module"))
    assertEquals(ProjectRootDiscovery.find(nested), Some(root.toAbsolutePath.normalize()))

  test("finds the nested project/build.properties marker"):
    val root = tempDir()
    Files.createDirectories(root.resolve("project"))
    Files.createFile(root.resolve("project/build.properties"))
    assertEquals(ProjectRootDiscovery.find(root), Some(root.toAbsolutePath.normalize()))

  test("returns None when no marker exists within range"):
    val root = tempDir()
    assertEquals(ProjectRootDiscovery.find(root), None)

  test("resolveDefaultRoot fails closed without a marker"):
    val root = tempDir()
    ProjectRootDiscovery.resolveDefaultRoot(root, skipCheck = false) match
      case Left(msg)  => assert(msg.contains("could not detect a Scala project root"))
      case Right(dir) => fail(s"expected a Left, got $dir")

  test("resolveDefaultRoot with skipCheck bypasses discovery"):
    val root = tempDir()
    assertEquals(
      ProjectRootDiscovery.resolveDefaultRoot(root, skipCheck = true),
      Right(root.toAbsolutePath.normalize())
    )

  test("resolveDefaultRoot succeeds when a marker is present"):
    val root = tempDir()
    Files.createFile(root.resolve("build.sbt"))
    assertEquals(
      ProjectRootDiscovery.resolveDefaultRoot(root, skipCheck = false),
      Right(root.toAbsolutePath.normalize())
    )
