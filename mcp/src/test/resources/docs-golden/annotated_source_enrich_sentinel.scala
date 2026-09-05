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
    def show(a: Int) = a.toString /*SEM:type: String:SEM*/

  given stringShow: Show[String] with
    def show(a: String) = a /*SEM:type: String:SEM*/

  // context bound `A: Show` desugars to a `(using Show[A])` parameter the source never writes
  given listShow[A: Show]: Show[List[A]] with /*SEM:type: Show[A]; elaborated: (using Show[A]):SEM*/
    def show(a: List[A]) = /*SEM:type: String:SEM*/
      a.map(Show[A].show).mkString("[", ", ", "]") /*SEM:elaborated: Show[A](using Show[A]):SEM*/

// `[A: Show]` again — the using-param and the `Show[A]` summon are both invisible in the text
def render[A: Show](a: A): String = Show[A].show(a) /*SEM:elaborated: Show[A](using Show[A]):SEM*/

extension (n: Int) def shown(using Show[Int]): String = render(n) /*SEM:elaborated: render[Int](n)(using Show[Int]):SEM*/

object Instances:
  given doubleShow: Show[Double] with
    def show(a: Double) = a.toString /*SEM:type: String:SEM*/
  given floatShow: Show[Float] with
    def show(a: Float) = a.toString /*SEM:type: String:SEM*/

// wildcard `given` import: the givens enter scope with NO written name at the summon site — only
// the symbols legend can say which given `render(3.14)` picked, and format=compilable explodes this
// line to the explicit `import Instances.{doubleShow, floatShow}` of just the givens actually used
import Instances.given

val nums = List(1, 2, 3) /*SEM:type: List[Int]; elaborated: List.apply[Int](1, 2, 3):SEM*/
val pi = render(3.14) /*SEM:type: String; elaborated: render[Double](3.14)(using doubleShow):SEM*/
val flo = render(1.0f) /*SEM:type: String; elaborated: render[Float](1.0f)(using floatShow):SEM*/
val out = render(nums) /*SEM:type: String; elaborated: render[List[Int]](nums)(using listShow(using intShow)):SEM*/
val sorted = nums.sorted /*SEM:type: List[Int]; elaborated: nums.sorted[Int](using Ordering[Int]):SEM*/
val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1) /*SEM:type: List[Tuple2[String, Int]]; elaborated: List.apply[Tuple2[String, Int]]("b" ->[Int] 2, "a" ->[Int] 1).sortBy[String](_._1)(using Ordering[String]):SEM*/
val labeled = nums.map(n => n -> render(n)) /*SEM:type: List[Tuple2[Int, String]]; elaborated: ArrowAssoc[Int](n); elaborated: render[Int](n)(using intShow):SEM*/
val total = nums.foldLeft(0)(_ + _) /*SEM:type: Int; nums.foldLeft[Int]:SEM*/
val ratio: Double = nums.size /*SEM:elaborated: int2double(nums.size):SEM*/
val shownFive = 5.shown /*SEM:type: String; elaborated: 5.shown(using intShow):SEM*/
val firstTwo = /*SEM:type: Option[String]:SEM*/
  for
    a <- nums.headOption
    b <- sorted.headOption
  yield render(a) + render(b) /*SEM:elaborated: render[Int](a)(using intShow); elaborated: render[Int](b)(using intShow):SEM*/
