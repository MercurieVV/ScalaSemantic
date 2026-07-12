package com.github.mercurievv.scalasemantic.docexamples

trait Show[A]:
  def show(a: A): String

given intShow: Show[Int] with
  def show(a: Int) = a.toString

given listShow[A](using sh: Show[A]): Show[List[A]] with
  def show(a: List[A]) = a.map(sh.show).mkString("[", ", ", "]")

def render[A](a: A)(using sh: Show[A]): String =
  sh.show(a)

val out = render(List(1, 2, 3))
val num = render(42)
