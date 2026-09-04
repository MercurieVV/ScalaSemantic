package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path

/** Validates that a default (unspecified) server root actually looks like a Scala project root,
  * instead of silently trusting whatever cwd the MCP client happened to launch with. See ADR-0003.
  */
private[scalasemantic] object ProjectRootDiscovery:
  private val Markers = Seq(
    "build.mill",
    "build.sc",
    "build.sbt",
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "project.scala"
  )
  private val NestedMarkers = Seq(Path.of("project", "build.properties"))
  private val MaxWalkUp = 8

  private def hasMarker(dir: Path): Boolean =
    Markers.exists(m => Files.exists(dir.resolve(m))) ||
      NestedMarkers.exists(m => Files.exists(dir.resolve(m)))

  /** `cwd` is trusted as-is when it already carries a marker; otherwise ancestors are searched up
    * to `MaxWalkUp` levels, stopping at `$HOME` or the filesystem root. Returns `None` when no
    * marker was found anywhere in that range.
    */
  def find(cwd: Path): Option[Path] =
    val start = cwd.toAbsolutePath.normalize()
    val home = sys.props.get("user.home").map(h => Path.of(h).toAbsolutePath.normalize())

    @annotation.tailrec
    def loop(dir: Path, remaining: Int): Option[Path] =
      if remaining < 0 || home.contains(dir) then None
      else if hasMarker(dir) then Some(dir)
      else
        Option(dir.getParent) match
          case Some(parent) => loop(parent, remaining - 1)
          case None         => None

    loop(start, MaxWalkUp)

  /** Resolves the server root for a default (`"."`) positional argument. `skipCheck` bypasses
    * discovery entirely (`SCALASEMANTIC_SKIP_ROOT_CHECK=1`), returning `cwd` unconditionally.
    */
  def resolveDefaultRoot(cwd: Path, skipCheck: Boolean): Either[String, Path] =
    if skipCheck then Right(cwd.toAbsolutePath.normalize())
    else
      find(cwd).toRight(
        s"scalasemantic-mcp: could not detect a Scala project root at or above '${cwd.toAbsolutePath.normalize()}' " +
          s"(looked for ${Markers.mkString(", ")}, or project/build.properties, within $MaxWalkUp levels). " +
          "Pass the project root explicitly (`serve /path/to/project`), or set " +
          "SCALASEMANTIC_SKIP_ROOT_CHECK=1 to use the launch directory as-is."
      )
