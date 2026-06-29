package com.github.mercurievv.scalasemantic.compat

/** Inheritance fixtures: exercises inherited-vs-declared members distinction. */

trait Greeter {
  def greet(): String = "hi"
}

/** Inherits `greet` without redeclaring it — exercises inherited-vs-declared members. */
class Robot extends Greeter
