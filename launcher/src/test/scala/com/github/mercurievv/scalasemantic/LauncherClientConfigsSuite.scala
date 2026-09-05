package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

class LauncherClientConfigsSuite extends munit.FunSuite:

  private def withTempProject(test: Path => Unit): Unit =
    val dir = Files.createTempDirectory("launcher-client-configs-suite")
    try test(dir)
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)

  test("relativizeCommand rewrites an absolute path inside the project to a relative one") {
    withTempProject { project =>
      val script = project.resolve("scalasemantic-mcp.sh")
      Files.writeString(script, "#!/bin/sh\n")
      assertEquals(
        LauncherClientConfigs.relativizeCommand(project, script.toAbsolutePath.toString),
        "./scalasemantic-mcp.sh"
      )
    }
  }

  test("relativizeCommand rewrites a nested absolute path with its subdirectory") {
    withTempProject { project =>
      val scriptsDir = project.resolve("scripts")
      Files.createDirectories(scriptsDir)
      val script = scriptsDir.resolve("scalasemantic-mcp.sh")
      Files.writeString(script, "#!/bin/sh\n")
      assertEquals(
        LauncherClientConfigs.relativizeCommand(project, script.toAbsolutePath.toString),
        "./scripts/scalasemantic-mcp.sh"
      )
    }
  }

  test("relativizeCommand leaves a bare PATH command untouched") {
    withTempProject { project =>
      assertEquals(
        LauncherClientConfigs.relativizeCommand(project, "scalasemantic-mcp"),
        "scalasemantic-mcp"
      )
    }
  }

  test("relativizeCommand leaves an already-relative command untouched") {
    withTempProject { project =>
      assertEquals(
        LauncherClientConfigs.relativizeCommand(project, "./scalasemantic-mcp.sh"),
        "./scalasemantic-mcp.sh"
      )
    }
  }

  test("relativizeCommand leaves an absolute command outside the project untouched") {
    withTempProject { project =>
      val outside = Files.createTempDirectory("launcher-outside")
      try
        val script = outside.resolve("scalasemantic-mcp.sh")
        Files.writeString(script, "#!/bin/sh\n")
        val absolute = script.toAbsolutePath.toString
        assertEquals(LauncherClientConfigs.relativizeCommand(project, absolute), absolute)
      finally
        Files
          .walk(outside)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete)
    }
  }

  // `project` and `home` are the same directory on purpose: if user-scope writes were still routed
  // through project relativization, the command would come out as "./.local/bin/scalasemantic-mcp".
  test("write under user scope emits an absolute command and writes under home") {
    withTempProject { home =>
      val launcher = home.resolve(".local/bin/scalasemantic-mcp")
      Files.createDirectories(launcher.getParent)
      Files.writeString(launcher, "#!/bin/sh\n")
      val opts = LauncherSetup.Options(
        project = home,
        client = "claude",
        command = launcher.toAbsolutePath.toString,
        scope = LauncherScope.User,
        home = home
      )
      LauncherClientConfigs.write(home, opts)
      val written = Files.readString(home.resolve(".claude.json"))
      assert(
        written.contains(launcher.toAbsolutePath.toString),
        s"expected the absolute launcher path in a user-scope config, got:\n$written"
      )
      assert(
        !Files.exists(home.resolve(".mcp.json")),
        "user scope must not write a project .mcp.json"
      )
    }
  }

  test("write emits a project-relative command for an in-project absolute launcher path") {
    withTempProject { project =>
      val script = project.resolve("scalasemantic-mcp.sh")
      Files.writeString(script, "#!/bin/sh\n")
      val opts = LauncherSetup.Options(
        project = project,
        client = "claude",
        command = script.toAbsolutePath.toString
      )
      LauncherClientConfigs.write(project, opts)
      val written = Files.readString(project.resolve(".mcp.json"))
      assert(
        written.contains("\"./scalasemantic-mcp.sh\""),
        s"expected a relative command in generated config, got:\n$written"
      )
      assert(
        !written.contains(script.toAbsolutePath.toString),
        s"generated config should not carry the absolute path:\n$written"
      )
    }
  }
