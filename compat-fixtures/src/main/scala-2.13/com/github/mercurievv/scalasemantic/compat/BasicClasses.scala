package com.github.mercurievv.scalasemantic.compat

/** Basic class hierarchy fixtures: traits, concrete classes, multiple inheritance. Shared between
  * Scala-2.13 and Scala-3 (mirrored in the scala-3 source tree).
  */

trait Animal {
  def name: String
}

trait Swimmer {
  def swim(): Unit
}

class Dog(val name: String) extends Animal {
  def fetch(): Unit = ()
}

class Fish(val name: String) extends Animal with Swimmer {
  def swim(): Unit = ()
}
