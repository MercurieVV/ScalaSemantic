package com.github.mercurievv.scalasemantic.compat

/** A product record whose usages live on generated members. Deliberately written in syntax valid on
  * both 2.13 and 3, and duplicated verbatim in the `scala-2.13` tree, because the two compilers
  * disagree about which symbol a `Point(0, 0)` construction site resolves to (3: the companion
  * object; 2.13: `<init>`). The compat suite pins that the related expansion covers the site on
  * either, without asserting which symbol carried it.
  */
case class Point(x: Int, y: Int)

object PointUses {
  val origin: Point = Point(0, 0)
  val shifted: Point = origin.copy(x = 1)
  def sum(p: Point): Int = p.x + p.y
  def isOrigin(p: Point): Boolean = p match {
    case Point(0, 0) => true
    case _           => false
  }
}
