package com.github.mercurievv.scalasemantic.docexamples

trait Show[A]:
  def show(a: A): String

given intShow: Show[Int] with
  def show(a: Int) = a.toString  // ⟹ : String

given listShow[A](using sh: Show[A]): Show[List[A]] with  // ⟹ (using sh)
  def show(a: List[A]) = a.map(sh.show).mkString("[", ", ", "]")  // ⟹ : String; col 26 [String]

def render[A](a: A)(using sh: Show[A]): String =
  sh.show(a)

val out = render(List(1, 2, 3))  // ⟹ : String; (using listShow); col 11 [List[Int]]; col 18 apply(…); col 18 [Int]; (using intShow)
val num = render(42)  // ⟹ : String; (using intShow); col 11 [Int]
