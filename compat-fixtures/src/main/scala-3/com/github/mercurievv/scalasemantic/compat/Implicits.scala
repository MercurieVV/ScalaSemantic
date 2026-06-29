package com.github.mercurievv.scalasemantic.compat

/** Given instances and chained derivation. Uses Scala-3 `given`/`using` syntax; the Scala-2.13
  * counterpart uses `implicit`. Feeds resolve-implicits and trace-implicit-chain tests.
  */
object Implicits:
  given intShow: Show[Int] with
    def show(a: Int): String = a.toString

  // Chained given: Show[List[A]] depends on a Show[A] — feeds trace-implicit-chain.
  given listShow[A](using s: Show[A]): Show[List[A]] with
    def show(a: List[A]): String = a.map(s.show).mkString(",")

  def render[A](a: A)(using sh: Show[A]): String = sh.show(a)
