package com.github.mercurievv.scalasemantic.docexamples

/** A small type class + derivations that deliberately exercise the compiler insertions a plain-text
  * read cannot see. Each line is here to trigger a DISTINCT invisible insertion: given summons,
  * context-bound desugaring, inferred result/value types, inferred type arguments (on plain calls,
  * on named calls, and on method selects), nested implicit arguments, implicit conversions,
  * ordering summons, numeric widening, extension resolution, and for-comprehension desugaring.
  */
trait Show[A]:
  def show(a: A): String

object Show:
  /** Summoner: a bare `Show[A]` at a use site expands to `(using <given>)`. */
  def apply[A](using s: Show[A]): Show[A] = s

  given intShow: Show[Int] with
    def show(a: Int) = a.toString

  given stringShow: Show[String] with
    def show(a: String) = a

  // context bound `A: Show` desugars to a `(using Show[A])` parameter the source never writes
  given listShow[A: Show]: Show[List[A]] with
    def show(a: List[A]) =
      a.map(Show[A].show).mkString("[", ", ", "]")

// `[A: Show]` again — the using-param and the `Show[A]` summon are both invisible in the text
def render[A: Show](a: A): String = Show[A].show(a)

extension (n: Int) def shown(using Show[Int]): String = render(n)

val nums = List(1, 2, 3)
val out = render(nums)
val sorted = nums.sorted
val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1)
val labeled = nums.map(n => n -> render(n))
val total = nums.foldLeft(0)(_ + _)
val ratio: Double = nums.size
val shownFive = 5.shown
val firstTwo =
  for
    a <- nums.headOption
    b <- sorted.headOption
  yield render(a) + render(b)
