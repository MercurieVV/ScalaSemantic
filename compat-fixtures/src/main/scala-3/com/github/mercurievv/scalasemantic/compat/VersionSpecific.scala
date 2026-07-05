package com.github.mercurievv.scalasemantic.compat

// Marker traits solely to give `intersection` below two unrelated types to combine with `&`;
// kept local to this file so it stays self-contained (no dependency on other compat fixtures).
trait Flyer:
  def fly(): Unit
trait Diver:
  def dive(): Unit

/** Scala-3-only type shapes: union/intersection/literal/constant types and extension methods. These
  * constructs do not exist in Scala-2.13; there is no counterpart in the scala-2.13 source tree.
  * They exercise the type printer against real 3.x-emitted SemanticDB.
  */
object VersionSpecific:
  def union(x: Int | String): Int | String = x
  def intersection(x: Flyer & Diver): Flyer & Diver = x
  def literal: 42 = 42

  extension (s: String) def shout: String = s.toUpperCase
