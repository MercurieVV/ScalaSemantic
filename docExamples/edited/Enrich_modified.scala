package com.github.mercurievv.scalasemantic.docexamples

// EDITED BUFFER — not compiled by the build (lives outside src/main/scala). Fed verbatim to the
// presentation compiler via the `source` parameter to demonstrate that tools answer correctly on
// code that was modified but never recompiled. The only change vs the committed Enrich.scala is the
// extra `prefix: String` using-parameter on `render`. See docs/usage/tool-examples.md.
trait Show[A]:
  def show(a: A): String

given intShow: Show[Int] with
  def show(a: Int) = a.toString

given listShow[A](using sh: Show[A]): Show[List[A]] with
  def show(a: List[A]) = a.map(sh.show).mkString("[", ", ", "]")

def render[A](a: A)(using sh: Show[A], prefix: String): String =
  prefix + sh.show(a)

val out = render(List(1, 2, 3))
val num = render(42)
