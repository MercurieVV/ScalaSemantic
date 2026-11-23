package com.github.mercurievv.scalasemantic.wire

import com.github.mercurievv.scalasemantic.model.Clause
import com.github.mercurievv.scalasemantic.model.Contract
import com.github.mercurievv.scalasemantic.model.ContractResult
import com.github.mercurievv.scalasemantic.model.Enclosing
import com.github.mercurievv.scalasemantic.model.FileContracts
import com.github.mercurievv.scalasemantic.model.FileLiterals
import com.github.mercurievv.scalasemantic.model.FileReferences
import com.github.mercurievv.scalasemantic.model.HoleContextResult
import com.github.mercurievv.scalasemantic.model.HoleExpected
import com.github.mercurievv.scalasemantic.model.HoleOmitted
import com.github.mercurievv.scalasemantic.model.Literal
import com.github.mercurievv.scalasemantic.model.LiteralResult
import com.github.mercurievv.scalasemantic.model.Reference
import com.github.mercurievv.scalasemantic.model.ReferenceResult
import io.circe.Encoder
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.SchemaType

/** The circe wire shape of the analysis models: field names are the case-class names, an absent
  * `Option` is `null` before the shared encode step and the schema marks it optional.
  */
class AnalysisCodecsSuite extends munit.FunSuite:
  import AnalysisCodecs.given

  private def json[A](a: A)(using e: Encoder[A]): Json = e(a).deepDropNullValues

  private def fieldNames(s: Schema[?]): List[String] = s.schemaType match
    case SchemaType.SProduct(fields) => fields.map(_.name.name)
    case _                           => Nil

  private def optional(s: Schema[?], field: String): Boolean = s.schemaType match
    case SchemaType.SProduct(fields) =>
      fields
        .find(_.name.name == field)
        .exists(f =>
          f.schema.schemaType match
            case SchemaType.SOption(_) => true
            case _                     => false
        )
    case _ => false

  test(
    "literal_tokens: an absent enclosing/error is an absent key, present values keep their names"
  ):
    val lit = Literal(3, 4, "int", "42", None)
    val enc = Literal(5, 6, "string", "x", Some(Enclosing("val", "A", 2)))
    val res = LiteralResult(List(FileLiterals("A.scala", List(lit, enc), 0, None)))
    val file = json(res).hcursor.downField("files").downArray
    assertEquals(
      file.keys.map(_.toList.sorted),
      Some(List("literals", "omitted", "uri")),
      "error: None is absent"
    )
    val lits = file.downField("literals").values.toList.flatten
    assertEquals(
      lits.head.asObject.map(_.keys.toList.sorted),
      Some(List("character", "kind", "line", "value"))
    )
    assertEquals(
      lits(1).hcursor.downField("enclosing").get[String]("name"),
      Right("A")
    )

  test("contract_clauses and resolve_references encode with their model field names"):
    val c = ContractResult(
      List(
        FileContracts(
          "A.scala",
          List(Contract("A.f", 1, List(Clause("x > 0", "cmp")), None, None)),
          None
        )
      )
    )
    val d = json(c).hcursor.downField("files").downArray.downField("defs").downArray
    assertEquals(d.keys.map(_.toList.sorted), Some(List("key", "line", "requires")))
    val r = ReferenceResult(
      List(
        FileReferences(
          "A.scala",
          List(Reference(1, 2, "n", "term", "use", resolved = false, None, None, List("m"))),
          0,
          Some("boom")
        )
      )
    )
    val ref = json(r).hcursor.downField("files").downArray
    assertEquals(ref.get[String]("error"), Right("boom"))
    assertEquals(
      ref.downField("references").downArray.keys.map(_.toList.sorted),
      Some(List("character", "kind", "line", "name", "resolved", "role", "suggestions"))
    )

  test("hole_context encodes the `type` and `match` fields under their own names"):
    val h = HoleContextResult(
      found = true,
      why = None,
      hole = None,
      expected = Some(HoleExpected(Some("Int"), "high", "because")),
      bindings = Nil,
      producers = Nil,
      givens = Nil,
      types = Nil,
      omitted = HoleOmitted(0, 0, 0, 0)
    )
    val out = json(h)
    assertEquals(out.hcursor.downField("expected").get[String]("type"), Right("Int"))
    assert(!out.asObject.exists(_.contains("why")), "absent Option is an absent key")

  test("schemas are derived from the same types: optional fields are the Options"):
    assert(optional(summon[Schema[FileLiterals]], "error"))
    assert(!optional(summon[Schema[FileLiterals]], "uri"))
    assert(optional(summon[Schema[Literal]], "enclosing"))
    assert(optional(summon[Schema[HoleContextResult]], "why"))
    assertEquals(
      fieldNames(summon[Schema[FileReferences]]).toSet,
      Set("uri", "references", "omitted", "error")
    )
