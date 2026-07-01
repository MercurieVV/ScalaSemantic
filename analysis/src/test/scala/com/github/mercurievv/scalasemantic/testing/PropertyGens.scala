package com.github.mercurievv.scalasemantic.testing

import com.github.mercurievv.scalasemantic.model.Location
import com.github.mercurievv.scalasemantic.model.MethodSignature
import com.github.mercurievv.scalasemantic.model.Parameter
import com.github.mercurievv.scalasemantic.model.ParameterList
import com.github.mercurievv.scalasemantic.model.Position
import com.github.mercurievv.scalasemantic.model.Range
import com.github.mercurievv.scalasemantic.model.SymbolKind
import com.github.mercurievv.scalasemantic.model.SymbolRef
import org.scalacheck.Gen

object PropertyGens:

  val nonEmptyString: Gen[String] =
    Gen.alphaStr.suchThat(_.nonEmpty)

  val position: Gen[Position] =
    for
      line <- Gen.chooseNum(0, 1000)
      character <- Gen.chooseNum(0, 1000)
    yield Position(line, character)

  val orderedRange: Gen[Range] =
    for
      startLine <- Gen.chooseNum(0, 1000)
      startChar <- Gen.chooseNum(0, 1000)
      endLine <- Gen.chooseNum(startLine, startLine + 10)
      endChar <-
        if endLine == startLine then Gen.chooseNum(startChar + 1, startChar + 100)
        else Gen.chooseNum(0, 1000)
    yield Range(Position(startLine, startChar), Position(endLine, endChar))

  val location: Gen[Location] =
    for
      uri <- nonEmptyString
      range <- orderedRange
    yield Location(uri, range)

  val symbolRef: Gen[SymbolRef] =
    for
      symbol <- nonEmptyString
      displayName <- nonEmptyString
      kind <- Gen.oneOf(SymbolKind.values.toSeq)
    yield SymbolRef(symbol, displayName, kind)

  val parameter: Gen[Parameter] =
    for
      name <- nonEmptyString
      tpe <- nonEmptyString
      isImplicit <- Gen.oneOf(true, false)
    yield Parameter(name, tpe, isImplicit)

  val parameterList: Gen[ParameterList] =
    for
      parameters <- Gen.listOf(parameter)
      isImplicit <- Gen.oneOf(true, false)
    yield ParameterList(parameters, isImplicit)

  val methodSignature: Gen[MethodSignature] =
    for
      symbol <- nonEmptyString
      displayName <- nonEmptyString
      typeParameters <- Gen.listOf(nonEmptyString)
      parameterLists <- Gen.listOf(parameterList)
      returnType <- nonEmptyString
      rendered <- nonEmptyString
    yield MethodSignature(symbol, displayName, typeParameters, parameterLists, returnType, rendered)

  val pointInsideRange: Gen[(Range, Position)] =
    orderedRange.map(range => (range, range.start))

  def nodes(maxN: Int): Gen[Set[String]] =
    Gen.chooseNum(0, maxN).map(n => (0 until n).map(_.toString).toSet)

  def graph(nodes: Set[String]): Gen[Map[String, Set[String]]] =
    if nodes.isEmpty then Gen.const(Map.empty)
    else
      val nodeList = nodes.toList
      Gen
        .sequence[List[Set[String]], Set[String]](
          nodeList.map(_ => Gen.someOf(nodeList).map(_.toSet))
        )
        .map(edges => nodeList.zip(edges).toMap)

  val nodeGraph: Gen[(Set[String], Map[String, Set[String]])] =
    for
      nodes <- nodes(6)
      graph <- graph(nodes)
    yield (nodes, graph)
