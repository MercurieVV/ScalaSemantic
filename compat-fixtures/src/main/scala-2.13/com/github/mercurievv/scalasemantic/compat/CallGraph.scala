package com.github.mercurievv.scalasemantic.compat

/** Flat call chain a -> b -> c for call-graph path-finding tests. */
object Calls {
  def a(): Int = b()
  def b(): Int = c()
  def c(): Int = 1
}
