package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.pc.HoleContext as H

class HoleContextModelSuite extends munit.FunSuite:
  private val pcResult = H.Result(
    found = true,
    why = None,
    hole = Some(H.Hole("???", "???")),
    expected = Some(H.Expected(Some("IO[Unit]"), H.Confidence.Exact, "result type of def effect")),
    bindings = List(H.Binding("release", "IO[Unit]", "param", "enclosing def run")),
    producers = List(H.Producer("IO.unit", "IO[Unit]", "companion IO", "exact")),
    givens = Nil,
    types = List(
      H.TypeRef("cats/effect/IO#", "IO", "sealed abstract class IO[+A]"),
      H.TypeRef("demo/Show#", "Show", "trait Show[A]")
    ),
    omitted = H.Omitted(0, 3, 0, 0)
  )

  test("conversion keeps every field and lower-cases the confidence") {
    val m = HoleContextResult.from(
      pcResult,
      s => if s == "demo/Show#" then Some("trait Show[A]:\n  def show(a: A): String") else None
    )
    assertEquals(m.expected.map(_.confidence), Some("exact"))
    assertEquals(m.producers.map(_.`match`), List("exact"))
    assertEquals(m.omitted.producers, 3)
  }

  test("types: project types get source=project and an excerpt, classpath types source=classpath") {
    val m = HoleContextResult.from(
      pcResult,
      s => if s == "demo/Show#" then Some("trait Show[A]") else None
    )
    assertEquals(
      m.types.map(t => t.symbol -> t.source),
      List("cats/effect/IO#" -> "classpath", "demo/Show#" -> "project")
    )
    assertEquals(m.types.find(_.symbol == "demo/Show#").flatMap(_.excerpt), Some("trait Show[A]"))
  }

  test("JSON shape: keys named as the spec, confidence unconstrained serialises with empty lists") {
    val m = HoleContextResult.from(
      pcResult.copy(
        expected = Some(H.Expected(None, H.Confidence.Unconstrained, "no type prescribed here")),
        bindings = Nil,
        producers = Nil
      ),
      _ => None
    )
    val json = ujson.read(upickle.default.write(m))
    assertEquals(json("expected")("confidence").str, "unconstrained")
    assertEquals(json("bindings").arr.size, 0)
    assert(json.obj.keySet.contains("omitted"))
  }
