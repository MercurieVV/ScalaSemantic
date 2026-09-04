package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

class LauncherSetupSuite extends munit.FunSuite:

  private def withTempDir(prefix: String)(test: Path => Unit): Unit =
    val dir = Files.createTempDirectory(prefix)
    try test(dir)
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)

  test("parse defaults to project scope") {
    assertEquals(LauncherSetup.parse(Nil).scope, LauncherScope.Project)
  }

  test("parse accepts --scope user") {
    assertEquals(LauncherSetup.parse(List("--scope", "user")).scope, LauncherScope.User)
  }

  test("parse accepts --scope project") {
    assertEquals(LauncherSetup.parse(List("--scope", "project")).scope, LauncherScope.Project)
  }

  // Regression: the JVM derives `user.home` from the OS account, not from the environment, so
  // defaulting to it made a sandboxed install write into the developer's real home directory.
  test("edits are only reminded about, not denied, unless --strict-edits is passed") {
    assertEquals(LauncherSetup.parse(Nil).strictEdits, false)
    assertEquals(LauncherSetup.parse(List("--strict-edits")).strictEdits, true)
    assertEquals(
      LauncherSetup.parse(List("--strict-edits", "--no-strict-edits")).strictEdits,
      false
    )
  }

  test("--strict-edits composes with the other setup flags") {
    val opts =
      LauncherSetup.parse(List("--scope", "project", "--strict-edits", "--client", "claude"))
    assertEquals(opts.strictEdits, true)
    assertEquals(opts.client, "claude")
    assertEquals(opts.scope, LauncherScope.Project)
  }

  test("Options.home follows $HOME") {
    sys.env.get("HOME").foreach { h =>
      assertEquals(LauncherSetup.Options().home, Path.of(h).toAbsolutePath.normalize())
    }
  }

  test("targetPathFor resolves user-scope paths under home") {
    withTempDir("launcher-home") { home =>
      val opts = LauncherSetup.Options(scope = LauncherScope.User, home = home)
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "claude"),
        Some(home.resolve(".claude.json"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "codex"),
        Some(home.resolve(".codex/config.toml"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "gemini"),
        Some(home.resolve(".gemini/settings.json"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "continue"),
        Some(home.resolve(".continue/config.yaml"))
      )
    }
  }

  test("targetPathFor resolves project-scope paths under the project") {
    withTempDir("launcher-project") { project =>
      val opts = LauncherSetup.Options(project = project, scope = LauncherScope.Project)
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "claude"),
        Some(project.resolve(".mcp.json"))
      )
      assertEquals(
        LauncherClientConfigs.targetPathFor(opts, "cline"),
        Some(project.resolve(".cline/mcp.json"))
      )
    }
  }

  test("targetPathFor returns None for a client with no user-scope config") {
    withTempDir("launcher-home-none") { home =>
      val opts = LauncherSetup.Options(scope = LauncherScope.User, home = home)
      LauncherClientConfigs.clientsWithoutUserScope.foreach { client =>
        assertEquals(
          LauncherClientConfigs.targetPathFor(opts, client),
          None,
          s"expected no user-scope path for '$client'"
        )
      }
    }
  }

  test("targetPathFor returns None for an unknown client in either scope") {
    assertEquals(LauncherClientConfigs.targetPathFor(LauncherSetup.Options(), "notaclient"), None)
  }
