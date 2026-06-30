package com.github.mercurievv.scalasemantic.compat

/** Scala-3-only type shapes: union/intersection/literal/constant types and extension methods. These
  * constructs do not exist in Scala-2.13; there is no counterpart in the scala-2.13 source tree.
  * They exercise the type printer against real 3.x-emitted SemanticDB.
  */
object VersionSpecific:
  def union(x: Int | String): Int | String = x
  def intersection(x: Animal & Swimmer): Animal & Swimmer = x
  def literal: 42 = 42

  extension (s: String) def shout: String = s.toUpperCase
