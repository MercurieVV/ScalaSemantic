package com.github.mercurievv.scalasemantic.fixtures

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

trait Greeter:
  def greet(): String = "hi"

/** Inherits `greet` without overriding — exercises inherited-vs-declared members. */
class Robot extends Greeter

trait Show[A]:
  def show(a: A): String

/** A type class with exactly one given instance — exercises single-candidate resolution. */
trait Eq[A]:
  def eqv(a: A, b: A): Boolean

/** A parent declaring one overload of `foo` and a subclass adding two more — exercises
  * `find_overloads` picking up same-named methods inherited from an ancestor type, distinct from
  * the same-owner overloads it already reported.
  */
trait OverloadParent:
  def foo(x: Long): Long = x

class OverloadChild extends OverloadParent:
  def foo(x: Int): Int = x
  def foo(x: String): String = x

object Sample:
  given intShow: Show[Int] with
    def show(a: Int): String = a.toString

  given intEq: Eq[Int] with
    def eqv(a: Int, b: Int): Boolean = a == b

  // Chained given: resolving Show[List[A]] depends on a Show[A] — feeds trace-implicit-chain.
  given listShow[A](using s: Show[A]): Show[List[A]] with
    def show(a: List[A]): String = a.map(s.show).mkString(",")

  def render[A](a: A)(using sh: Show[A]): String = sh.show(a)

  def over(x: Int): Int = x
  def over(x: String): String = x

/** A flat call chain a -> b -> c for call-graph path-finding. */
object Calls:
  def a(): Int = b()
  def b(): Int = c()
  def c(): Int = 1

/** A product record whose usages live on generated members: `Order(...)` construction resolves to
  * the companion object, not to `Order#`, so a plain type-reference search misses every one of the
  * sites exercised in [[OrderUses]].
  */
case class Order(id: Int, item: String)

object OrderUses:
  val first: Order = Order(1, "widget")
  val renamed: Order = first.copy(item = "gadget")
  def label(o: Order): String = o.item + "#" + o.id
  def isFirst(o: Order): Boolean = o match
    case Order(1, _) => true
    case _           => false
