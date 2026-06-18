package scalasemantic.fixtures

/** Fixtures with traits, subtyping, type classes, using-params and overloads, so the analyzer tests
  * have real SemanticDB structure to query (richer than the analyzer's own sources).
  */

trait Animal:
  def name: String

trait Swimmer:
  def swim(): Unit

class Dog(val name: String) extends Animal:
  def fetch(): Unit = ()

class Fish(val name: String) extends Animal, Swimmer:
  def swim(): Unit = ()

trait Show[A]:
  def show(a: A): String

object Sample:
  given intShow: Show[Int] with
    def show(a: Int): String = a.toString

  def render[A](a: A)(using sh: Show[A]): String = sh.show(a)

  def over(x: Int): Int = x
  def over(x: String): String = x
