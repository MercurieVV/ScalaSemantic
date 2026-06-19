# Examples

These are representative MCP tool calls and compact responses. Paths and line numbers are examples;
the important part is the shape of the query and what kind of answer the tool returns.

The examples use code like this:

```scala
package com.github.mercurievv.scalasemantic.fixtures

trait Animal:
  def name: String

class Dog(val name: String) extends Animal:
  def fetch(): Unit = ()

class Fish(val name: String) extends Animal:
  def swim(): Unit = ()

trait Show[A]:
  def show(a: A): String

object Sample:
  given intShow: Show[Int] with
    def show(a: Int): String = a.toString

  given listShow[A](using s: Show[A]): Show[List[A]] with
    def show(a: List[A]): String = a.map(s.show).mkString(",")

  def render[A](a: A)(using sh: Show[A]): String = sh.show(a)

object Calls:
  def a(): Int = b()
  def b(): Int = c()
  def c(): Int = 1
```

The Scala snippets below show the equivalent analyzer call. MCP clients normally send the JSON
request instead.

```scala
import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.mcp.{Mcp, McpTools, Tool}
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

val index = SemanticIndex.fromProject(".")
val analyzer = Analyzer(index)
val tools = McpTools.all(analyzer)

def toolJsonUsing(activeTools: List[Tool], name: String, arguments: ujson.Value): String =
  val request = ujson.Obj(
    "jsonrpc" -> "2.0",
    "id" -> 1,
    "method" -> "tools/call",
    "params" -> ujson.Obj("name" -> name, "arguments" -> arguments)
  )
  val response = Mcp.handle(request, activeTools).get
  val text = response("result")("content")(0)("text").str
  ujson.read(text).render(indent = 2)

def toolJson(name: String, arguments: ujson.Value): String =
  toolJsonUsing(tools, name, arguments)

val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"
val Show = "com/github/mercurievv/scalasemantic/fixtures/Show#"
val Render = "com/github/mercurievv/scalasemantic/fixtures/Sample.render()."
val CallsA = "com/github/mercurievv/scalasemantic/fixtures/Calls.a()."
val CallsC = "com/github/mercurievv/scalasemantic/fixtures/Calls.c()."
```

## Find a symbol by name

Scala call:

```scala
analyzer.findSymbol(
  query = "Animal",
  exact = true,
  kind = Some("TRAIT"),
  pathFilter = Some("*fixtures*")
)
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "find_symbol",
    "arguments": {
      "query": "Animal",
      "exact": true,
      "kind": "TRAIT",
      "pathFilter": "*fixtures*"
    }
  }
}
```

Response check:

```scala
val actual = toolJson(
  "find_symbol",
  ujson.Obj(
    "query" -> "Animal",
    "exact" -> true,
    "kind" -> "TRAIT",
    "pathFilter" -> "*fixtures*"
  )
)

val expected =
  """{
    |  "query": "Animal",
    |  "count": 1,
    |  "symbols": [
    |    {
    |      "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
    |      "name": "Animal",
    |      "kind": "TRAIT"
    |    }
    |  ]
    |}""".stripMargin

assert(actual == expected)
```

With grep: `grep -R "Animal" .` finds every textual `Animal`: the trait, subtypes, comments, string
literals, docs, and unrelated names such as `AnimalReport`. It does not give you the SemanticDB symbol
that the other tools need.

## Find exact usages of a symbol

Scala call:

```scala
analyzer.findUsages(Animal)
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "find_usages",
    "arguments": {
      "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
      "include": []
    }
  }
}
```

Response check:

```scala
val actual = toolJson(
  "find_usages",
  ujson.Obj(
    "symbol" -> Animal,
    "include" -> ujson.Arr()
  )
)

val expected =
  """{
    |  "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
    |  "name": "Animal",
    |  "referenceCount": 2
    |}""".stripMargin

assert(actual == expected)
```

With grep: `grep -R "Animal" .` cannot distinguish a definition from a reference, or this exact trait
from another `Animal` in a different package. It also misses references that do not spell the name the
same way after imports or aliases.

## Ask for class hierarchy

Scala call:

```scala
analyzer.classHierarchy(Animal).map(_.knownSubtypes.map(_.displayName))
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "class_hierarchy",
    "arguments": {
      "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
      "include": ["knownSubtypes"]
    }
  }
}
```

Response check:

```scala
val actual = toolJson(
  "class_hierarchy",
  ujson.Obj(
    "symbol" -> Animal,
    "include" -> ujson.Arr("knownSubtypes")
  )
)

val expected =
  """{
    |  "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
    |  "name": "Animal",
    |  "knownSubtypes": [
    |    "Dog",
    |    "Fish"
    |  ]
    |}""".stripMargin

assert(actual == expected)
```

With grep: `grep -R "extends Animal" .` only catches one spelling of direct inheritance. It can miss
indirect inheritance, type aliases, generated sources, or formatting that does not match the pattern,
and it still cannot prove that the `Animal` text resolved to this trait.

## Inspect a method signature

Scala call:

```scala
analyzer.methodSignature(Render).map(_.rendered)
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "method_signature",
    "arguments": {
      "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.render()."
    }
  }
}
```

Response check:

```scala
val actual = toolJson(
  "method_signature",
  ujson.Obj("symbol" -> Render)
)

val expected =
  """{
    |  "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.render().",
    |  "signature": "def render[A](a: A)(implicit sh: Show[A]): String"
    |}""".stripMargin

assert(actual == expected)
```

With grep: `grep -R "def render" .` can find the source line, but it does not resolve overloads,
inherited methods, synthetic information, or the exact SemanticDB method symbol. It also does not
normalize signatures into a stable one-line answer for an agent.

## Resolve implicit or given candidates

Scala call:

```scala
analyzer.resolveImplicits(Show).candidates.map(candidate =>
  candidate.target.displayName -> candidate.tpe
)
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "resolve_implicits",
    "arguments": {
      "type": "com/github/mercurievv/scalasemantic/fixtures/Show#"
    }
  }
}
```

Response check:

```scala
val actual = toolJson(
  "resolve_implicits",
  ujson.Obj("type" -> Show)
)

val expected =
  """{
    |  "type": "com/github/mercurievv/scalasemantic/fixtures/Show#",
    |  "candidates": [
    |    {
    |      "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.intShow.",
    |      "type": "Show[Int]"
    |    },
    |    {
    |      "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.listShow().",
    |      "type": "Show[List[A]]"
    |    }
    |  ]
    |}""".stripMargin

assert(actual == expected)
```

With grep: searching for `given`, `implicit`, or `Show` is noisy and incomplete. It cannot answer
"which values produce this type?" across imports, generic givens, inherited members, and renamed
symbols.

## Find a call path

Scala call:

```scala
analyzer.callPath(from = CallsA, to = CallsC).path.map(_.displayName)
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "call_path",
    "arguments": {
      "from": "com/github/mercurievv/scalasemantic/fixtures/Calls.a().",
      "to": "com/github/mercurievv/scalasemantic/fixtures/Calls.c()."
    }
  }
}
```

Response check:

```scala
val actual = toolJson(
  "call_path",
  ujson.Obj("from" -> CallsA, "to" -> CallsC)
)

val expected =
  """{
    |  "from": "a",
    |  "to": "c",
    |  "reachable": true,
    |  "path": [
    |    "a",
    |    "b",
    |    "c"
    |  ]
    |}""".stripMargin

assert(actual == expected)
```

With grep: `grep -R "c(" .` may show direct calls to `c`, but it will not compute the transitive path
from `a` through `b`. You would have to manually inspect callers, callees, overloads, and false
matches.

## Ask for type at a source position

Scala call:

```scala
import java.net.URI
import java.nio.file.Paths

val pc = PresentationCompilerBackend.fromCurrentJvm(workspace = Some(Paths.get("/abs/path")))
val analyzerWithPc = Analyzer(index, Some(pc))

val source =
  """package demo
    |class Widget:
    |  def area(w: Int): Int = w * 2
    |val broken: Int = "oops"
    |""".stripMargin

val liveAnalyzer =
  analyzerWithPc.withBuffer(
    fileUri = URI.create("file:///abs/path/Widget.scala"),
    code = source,
    docUri = "Widget.scala"
  )

try liveAnalyzer.typeAtPosition(uri = "Widget.scala", line = 2, character = 6)
finally pc.close()
```

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "tools/call",
  "params": {
    "name": "type_at_position",
    "arguments": {
      "uri": "Widget.scala",
      "line": 2,
      "character": 6,
      "source": "package demo\nclass Widget:\n  def area(w: Int): Int = w * 2\nval broken: Int = \"oops\"\n"
    }
  }
}
```

Response check:

```scala
val toolsWithPc = McpTools.all(analyzerWithPc, Paths.get("/abs/path"))
val actual = toolJsonUsing(
  toolsWithPc,
  "type_at_position",
  ujson.Obj(
    "uri" -> "Widget.scala",
    "line" -> 2,
    "character" -> 6,
    "source" -> source
  )
)

val expected =
  """{
    |  "symbol": "demo/Widget#area().",
    |  "name": "area",
    |  "type": "Int"
    |}""".stripMargin

assert(actual == expected)
```

With grep: a byte search can find the word `area`, but it cannot tell you the resolved symbol or type
at a specific cursor position. It also cannot use the current unsaved buffer; this tool can use the
presentation compiler overlay when `source` is provided.
