package com.github.mercurievv.scalasemantic.compat

/** Scala-3 compat fixtures. Mirrors the shapes in `scala-2.13/CompatFixtures.scala` so the same
  * structural assertions hold across compiler versions, plus Scala-3-only constructs (given/using,
  * extension, union/intersection/literal types) that stress the type printer.
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

/** Inherits `greet` without redeclaring it — exercises inherited-vs-declared members. */
class Robot extends Greeter

trait Show[A]:
  def show(a: A): String

object CompatFixtures:
  given intShow: Show[Int] with
    def show(a: Int): String = a.toString

  // Chained given: Show[List[A]] depends on a Show[A] — feeds trace-implicit-chain.
  given listShow[A](using s: Show[A]): Show[List[A]] with
    def show(a: List[A]): String = a.map(s.show).mkString(",")

  def render[A](a: A)(using sh: Show[A]): String = sh.show(a)

  // Overloads sharing a name + owner.
  def over(x: Int): Int = x
  def over(x: String): String = x

  // Scala-3-only type shapes for the printer: union, intersection, literal/constant type.
  def union(x: Int | String): Int | String = x
  def intersection(x: Animal & Swimmer): Animal & Swimmer = x
  def literal: 42 = 42

  // extension method
  extension (s: String) def shout: String = s.toUpperCase

/** Flat call chain a -> b -> c for call-graph path-finding. */
object Calls:
  def a(): Int = b()
  def b(): Int = c()
  def c(): Int = 1
