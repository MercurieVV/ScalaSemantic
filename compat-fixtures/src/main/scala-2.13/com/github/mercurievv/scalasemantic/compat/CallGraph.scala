package com.github.mercurievv.scalasemantic.compat

// Virtual/Polymorphic dispatch call graph
trait VirtualBase {
  def name(): String
}
class VirtualImpl1 extends VirtualBase {
  override def name(): String = "Impl1"
}
class VirtualImpl2 extends VirtualBase {
  override def name(): String = "Impl2"
}

object PolymorphicCalls {
  def callVirtual(base: VirtualBase): String = base.name()

  def entry1(): String = callVirtual(new VirtualImpl1())
  def entry2(): String = callVirtual(new VirtualImpl2())
}

// Implicit call path (Scala 2 extension methods via implicit class). The implicit class lives
// here (not a separate vendored file) since it exists solely to give this call-path fixture
// something to dispatch through.
object RichExtensions {
  implicit class RichString(val s: String) extends AnyVal {
    def shout: String = s.toUpperCase()
  }
}

object ImplicitCalls {
  import RichExtensions._

  def triggerShout(str: String): String = {
    // calling str.shout triggers implicit conversion to RichString and calling its shout method.
    str.shout
  }
}
