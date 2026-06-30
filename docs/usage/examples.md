# Examples

Representative MCP tool calls and responses. The fixture code used in each example:

```scala
package com.github.mercurievv.scalasemantic.fixtures

trait Animal:
  def name: String

class Dog(val name: String) extends Animal
class Fish(val name: String) extends Animal

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

## Find a symbol by name

```json
{
  "name": "find_symbol",
  "arguments": { "query": "Animal", "exact": true, "kind": "TRAIT", "pathFilter": "*fixtures*" }
}
```

Response:

```json
{
  "query": "Animal",
  "count": 1,
  "symbols": [{ "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#", "name": "Animal", "kind": "TRAIT" }]
}
```

With grep: `grep -R "Animal" .` returns the trait definition, subtypes, `AnimalReport`, string literals, and comments — no SemanticDB symbol, no filtering by kind.

## Find exact usages of a symbol

```json
{
  "name": "find_usages",
  "arguments": { "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#", "include": [] }
}
```

Response:

```json
{
  "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
  "name": "Animal",
  "referenceCount": 2
}
```

With grep: cannot distinguish definition from reference, or this exact trait from another `Animal` in a different package. Misses import aliases and renamed references.

## Ask for class hierarchy

```json
{
  "name": "class_hierarchy",
  "arguments": {
    "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
    "include": ["knownSubtypes"]
  }
}
```

Response:

```json
{
  "symbol": "com/github/mercurievv/scalasemantic/fixtures/Animal#",
  "name": "Animal",
  "knownSubtypes": ["Dog", "Fish"]
}
```

With grep: `grep -R "extends Animal"` misses indirect inheritance and type aliases, and cannot prove the `Animal` text resolved to this specific trait.

## Inspect a method signature

```json
{
  "name": "method_signature",
  "arguments": { "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.render()." }
}
```

Response:

```json
{
  "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.render().",
  "signature": "def render[A](a: A)(implicit sh: Show[A]): String"
}
```

With grep: `grep -R "def render"` finds source lines but does not resolve overloads, inherited methods, or synthetic information.

## Resolve implicit / given candidates

```json
{
  "name": "resolve_implicits",
  "arguments": { "type": "com/github/mercurievv/scalasemantic/fixtures/Show#" }
}
```

Response:

```json
{
  "type": "com/github/mercurievv/scalasemantic/fixtures/Show#",
  "candidates": [
    { "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.intShow.", "type": "Show[Int]" },
    { "symbol": "com/github/mercurievv/scalasemantic/fixtures/Sample.listShow().", "type": "Show[List[A]]" }
  ]
}
```

With grep: searching `given` or `Show` is noisy and cannot answer "which values produce this type?" across imports, generic givens, and inherited members.

## Find a call path

```json
{
  "name": "call_path",
  "arguments": {
    "from": "com/github/mercurievv/scalasemantic/fixtures/Calls.a().",
    "to": "com/github/mercurievv/scalasemantic/fixtures/Calls.c()."
  }
}
```

Response:

```json
{
  "from": "a",
  "to": "c",
  "reachable": true,
  "path": ["a", "b", "c"]
}
```

With grep: `grep -R "c("` finds direct calls but cannot compute the transitive path from `a` through `b`.

## Ask for type at a source position

```json
{
  "name": "type_at_position",
  "arguments": {
    "uri": "Widget.scala",
    "line": 2,
    "character": 6,
    "source": "package demo\nclass Widget:\n  def area(w: Int): Int = w * 2\nval broken: Int = \"oops\"\n"
  }
}
```

Response:

```json
{
  "symbol": "demo/Widget#area().",
  "name": "area",
  "type": "Int"
}
```

When `source` is provided, the server uses the presentation compiler to analyze the current buffer — no compile step needed. With grep: cannot determine the resolved symbol or type at a specific cursor position, and cannot use an unsaved buffer.
