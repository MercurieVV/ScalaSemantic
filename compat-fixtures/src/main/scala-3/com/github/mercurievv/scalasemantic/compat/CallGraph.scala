package com.github.mercurievv.scalasemantic.compat

/** Flat call chain a -> b -> c for call-graph path-finding tests. */
object Calls:
  def a(): Int = b()
  def b(): Int = c()
  def c(): Int = 1

// Virtual/Polymorphic dispatch call graph
trait VirtualBase:
  def name(): String

class VirtualImpl1 extends VirtualBase:
  override def name(): String = "Impl1"

class VirtualImpl2 extends VirtualBase:
  override def name(): String = "Impl2"

object PolymorphicCalls:
  def callVirtual(base: VirtualBase): String = base.name()

  def entry1(): String = callVirtual(new VirtualImpl1())
  def entry2(): String = callVirtual(new VirtualImpl2())

// Implicit call path (Scala 3 extension methods)
object ImplicitCalls:
  extension (s: String) def shout2: String = s.toUpperCase()

  def triggerShout(str: String): String =
    str.shout2
