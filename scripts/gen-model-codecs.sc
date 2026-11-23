//> using scala 3.8.4
// Generates wire/.../ModelCodecs.scala: a circe Encoder.AsObject + Decoder for every top-level
// `case class` in analysis/.../model/Models.scala (SymbolKind, an enum, gets a string codec).
// Usage: scala-cli run gen-model-codecs.sc -- <repoRoot>
import java.nio.file.{Files, Path, Paths}

val repo = Paths.get(args(0))
val models = repo.resolve("domain/src/main/scala/com/github/mercurievv/scalasemantic/model/Models.scala")
val src = Files.readString(models)
val caseClass = """(?m)^(?:final )?case class (\w+)""".r
val names = caseClass.findAllMatchIn(src).map(_.group(1)).toList

val imports = (names :+ "SymbolKind").sorted
  .map(n => s"import com.github.mercurievv.scalasemantic.model.$n")
  .mkString("\n")

val body = names
  .map(n => s"""  given Encoder.AsObject[$n] = Encoder.AsObject.derived
     |  given Decoder[$n] = Decoder.derived""".stripMargin)
  .mkString("\n")

val out =
  s"""package com.github.mercurievv.scalasemantic.wire
     |
     |$imports
     |import io.circe.Decoder
     |import io.circe.Encoder
     |
     |/** Circe codecs for every analysis model in `Models.scala` (generated from its case-class list by
     |  * scripts/gen-model-codecs.sc; keep in sync when a model is added). The result-model codecs that
     |  * tools return as they are, with their schemas, live in [[AnalysisCodecs]].
     |  */
     |object ModelCodecs:
     |
     |  given Encoder[SymbolKind] = Encoder[String].contramap(_.value)
     |  given Decoder[SymbolKind] = Decoder[String].map(SymbolKind.from)
     |
     |$body
     |""".stripMargin

val target = repo.resolve("wire/src/main/scala/com/github/mercurievv/scalasemantic/wire/ModelCodecs.scala")
Files.writeString(target, out)
println(s"wrote ${names.size} model codecs to $target")
