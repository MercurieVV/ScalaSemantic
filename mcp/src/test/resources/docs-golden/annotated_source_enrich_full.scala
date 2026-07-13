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
    def show(a: Int) = a.toString  // ⟹ : String

  given stringShow: Show[String] with
    def show(a: String) = a  // ⟹ : String

  // context bound `A: Show` desugars to a `(using Show[A])` parameter the source never writes
  given listShow[A: Show]: Show[List[A]] with  // ⟹ (using evidence$1); : Show[A]
    def show(a: List[A]) =  // ⟹ : String
      a.map(Show[A].show).mkString("[", ", ", "]")  // ⟹ Show[A](using evidence$1)

// `[A: Show]` again — the using-param and the `Show[A]` summon are both invisible in the text
def render[A: Show](a: A): String = Show[A].show(a)  // ⟹ Show[A](using evidence$1)

extension (n: Int) def shown(using Show[Int]): String = render(n)  // ⟹ render[Int](n)(using x$2)

val nums = List(1, 2, 3)  // ⟹ : List[Int]; List.apply[Int](1, 2, 3)
val out = render(nums)  // ⟹ : String; render[List[Int]](nums)(using listShow(using intShow))
val sorted = nums.sorted  // ⟹ : List[Int]; nums.sorted[Int](using Int)
val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1)  // ⟹ : List[Tuple2[String, Int]]; List.apply[Tuple2[String, Int]][String]("b" ->[Int] 2, "a" ->[Int] 1).sortBy(_._1)(using String)
val labeled = nums.map(n => n -> render(n))  // ⟹ : List[Tuple2[Int, String]]; ArrowAssoc[Int](n); render[Int](n)(using intShow)
val total = nums.foldLeft(0)(_ + _)  // ⟹ : Int
val ratio: Double = nums.size  // ⟹ int2double(nums.size)
val shownFive = 5.shown  // ⟹ : String; 5.shown(using intShow)
val firstTwo =  // ⟹ : Option[String]
  for
    a <- nums.headOption
    b <- sorted.headOption
  yield render(a) + render(b)  // ⟹ render[Int](a)(using intShow); render[Int](b)(using intShow)
