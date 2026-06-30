package com.github.mercurievv.scalasemantic.compat

/** Overloaded methods sharing a name and owner — exercises findOverloads. */
object Overloads:
  def over(x: Int): Int = x
  def over(x: String): String = x
