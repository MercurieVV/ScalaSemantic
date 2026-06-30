# Quickstart

Shortest path for an sbt project.

1. **Add the sbt plugin**:
   ```scala
   // project/plugins.sbt
   addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
   // build.sbt
   enablePlugins(ScalaSemanticMcpPlugin)
   ```
2. **Generate rules and configs**:
   ```sh
   sbt mcpClientConfig
   ```
3. **Compile the project**:
   ```sh
   sbt compile
   ```

For manual configurations, non-sbt builds, or other integration options, see [Integration](integration.md).
