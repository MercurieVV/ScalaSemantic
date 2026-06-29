package com.github.mercurievv.scalasemantic.compat

/** Implicit instances and chained derivation. Uses Scala-2.13 `implicit` syntax; the Scala-3
  * counterpart uses `given`/`using`. Feeds resolve-implicits and trace-implicit-chain tests.
  */
object Implicits {
  implicit val intShow: Show[Int] = new Show[Int] {
    def show(a: Int): String = a.toString
  }

  // Chained implicit: Show[List[A]] depends on a Show[A] — feeds trace-implicit-chain.
  implicit def listShow[A](implicit s: Show[A]): Show[List[A]] = new Show[List[A]] {
    def show(a: List[A]): String = a.map(s.show).mkString(",")
  }

  def render[A](a: A)(implicit sh: Show[A]): String = sh.show(a)
}
