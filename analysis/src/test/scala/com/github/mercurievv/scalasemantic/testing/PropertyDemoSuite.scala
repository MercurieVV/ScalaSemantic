package com.github.mercurievv.scalasemantic.testing

import com.github.mercurievv.scalasemantic.analysis.PureKernels
import com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics
import com.github.mercurievv.scalasemantic.model.MethodSignature
import org.scalacheck.Prop.forAll
import upickle.default.read
import upickle.default.write

class PropertyDemoSuite extends munit.ScalaCheckSuite:

  property("MethodSignature round-trips through upickle with reusable generators"):
    forAll(PropertyGens.methodSignature) { signature =>
      read[MethodSignature](write(signature)) == signature
    }

  property("rangeContains accepts generated points inside generated ranges"):
    forAll(PropertyGens.pointInsideRange) { (range, point) =>
      PureKernels.rangeContains(
        range.start.line,
        range.start.character,
        range.end.line,
        range.end.character,
        point.line,
        point.character
      )
    }

  property("coupling reports exactly the generated graph nodes"):
    forAll(PropertyGens.nodeGraph) { (nodes, graph) =>
      GraphMetrics.coupling(nodes, graph).keySet == nodes
    }
