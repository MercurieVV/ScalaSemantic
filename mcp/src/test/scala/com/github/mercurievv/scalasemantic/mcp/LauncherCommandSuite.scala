package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files
import java.nio.file.Path

import com.github.mercurievv.scalasemantic.LauncherClientConfigs
import com.github.mercurievv.scalasemantic.LauncherSetup

/** The command written into every generated MCP client config. A bare name is only ever resolved
  * against PATH -- never against the spawn cwd -- so emitting one for a project-local launcher
  * yields a config that silently fails to connect, which is how this regressed.
  */
class LauncherCommandSuite extends munit.FunSuite:

  private val withProject = FunFixture[Path](
    setup = _ => Files.createTempDirectory("launcher-command"),
    teardown = dir =>
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p): Unit)
  )

  withProject.test("resolves the project-local launcher relative to the spawn cwd") { project =>
    Files.writeString(project.resolve("scalasemantic-mcp.sh"), "#!/bin/sh\n")
    assertEquals(LauncherSetup.resolveCommand(project, None, Map.empty), "./scalasemantic-mcp.sh")
  }

  withProject.test("falls back to the PATH name when the project has no launcher script") {
    project =>
      assertEquals(
        LauncherSetup.resolveCommand(project, None, Map.empty),
        LauncherSetup.DefaultCommand
      )
  }

  withProject.test("an explicit --command wins over the project-local launcher") { project =>
    Files.writeString(project.resolve("scalasemantic-mcp.sh"), "#!/bin/sh\n")
    assertEquals(
      LauncherSetup.resolveCommand(project, Some("/opt/custom/scalasemantic"), Map.empty),
      "/opt/custom/scalasemantic"
    )
  }

  withProject.test("SCALASEMANTIC_LAUNCHER inside the project is rewritten relative to it") {
    project =>
      val launcher = project.resolve("scalasemantic-mcp.sh")
      Files.writeString(launcher, "#!/bin/sh\n")
      // The shell launcher exports its own absolute path; hard-coding that would pin the config
      // to one machine's $HOME.
      val env = Map("SCALASEMANTIC_LAUNCHER" -> launcher.toString)
      assertEquals(LauncherSetup.resolveCommand(project, None, env), "./scalasemantic-mcp.sh")
  }

  withProject.test("SCALASEMANTIC_LAUNCHER outside the project stays absolute") { project =>
    val env = Map("SCALASEMANTIC_LAUNCHER" -> "/opt/tools/scalasemantic-mcp.sh")
    assertEquals(
      LauncherSetup.resolveCommand(project, None, env),
      "/opt/tools/scalasemantic-mcp.sh"
    )
  }

  withProject.test("a launcher under scripts/ is found and kept relative") { project =>
    val scripts = Files.createDirectories(project.resolve("scripts"))
    Files.writeString(scripts.resolve("scalasemantic-mcp.sh"), "#!/bin/sh\n")
    assertEquals(
      LauncherSetup.resolveCommand(project, None, Map.empty),
      "./scripts/scalasemantic-mcp.sh"
    )
  }

  withProject.test("the generated .mcp.json spawns the resolved command") { project =>
    Files.writeString(project.resolve("scalasemantic-mcp.sh"), "#!/bin/sh\n")
    val opts = LauncherSetup
      .Options(project = project, client = "claude")
      .copy(command = Some(LauncherSetup.resolveCommand(project, None, Map.empty)))
    LauncherClientConfigs.write(project, opts)

    val written = Files.readString(project.resolve(".mcp.json"))
    assert(
      written.contains("\"./scalasemantic-mcp.sh\""),
      s"expected the relative launcher in the generated config, got:\n$written"
    )
    assert(!written.contains("\"scalasemantic-mcp\""), s"bare PATH name leaked:\n$written")
  }
