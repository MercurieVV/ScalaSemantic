package com.github.mercurievv.scalasemantic.compat

/** Scala-2.13 compat fixtures. Mirrors `scala-3/CompatFixtures.scala` using 2.13 syntax (implicits
  * instead of given/using) so the analyzer can be exercised against real 2.13-emitted SemanticDB.
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

trait Greeter {
  def greet(): String = "hi"
}

/** Inherits `greet` without redeclaring it — exercises inherited-vs-declared members. */
class Robot extends Greeter

trait Show[A] {
  def show(a: A): String
}

object CompatFixtures {
  implicit val intShow: Show[Int] = new Show[Int] {
    def show(a: Int): String = a.toString
  }

  // Chained implicit: Show[List[A]] depends on a Show[A] — feeds trace-implicit-chain.
  implicit def listShow[A](implicit s: Show[A]): Show[List[A]] = new Show[List[A]] {
    def show(a: List[A]): String = a.map(s.show).mkString(",")
  }

  def render[A](a: A)(implicit sh: Show[A]): String = sh.show(a)

  // Overloads sharing a name + owner.
  def over(x: Int): Int = x
  def over(x: String): String = x
}

/** Flat call chain a -> b -> c for call-graph path-finding. */
object Calls {
  def a(): Int = b()
  def b(): Int = c()
  def c(): Int = 1
}
