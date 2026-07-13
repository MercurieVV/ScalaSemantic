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
  given listShow[A: Show]: Show[List[A]] with  // ⟹ : Show[A]
    def show(a: List[A]) =  // ⟹ : String
      a.map(Show[A].show).mkString("[", ", ", "]")  // ⟹ a.map[String]; (using Show[A])

// `[A: Show]` again — the using-param and the `Show[A]` summon are both invisible in the text
def render[A: Show](a: A): String = Show[A].show(a)  // ⟹ (using Show[A])

extension (n: Int) def shown(using Show[Int]): String = render(n)  // ⟹ (using Show[Int]); render[Int]

object Instances:
  given doubleShow: Show[Double] with
    def show(a: Double) = a.toString  // ⟹ : String
  given floatShow: Show[Float] with
    def show(a: Float) = a.toString  // ⟹ : String

// wildcard `given` import: the givens enter scope with NO written name at the summon site — only
// the symbols legend can say which given `render(3.14)` picked, and format=compilable explodes this
// line to the explicit `import Instances.{doubleShow, floatShow}` of just the givens actually used
import Instances.{doubleShow, floatShow}

val nums = List(1, 2, 3)  // ⟹ : List[Int]; List.apply[Int]
val pi = render(3.14)  // ⟹ : String; (using doubleShow); render[Double]
val flo = render(1.0f)  // ⟹ : String; (using floatShow); render[Float]
val out = render(nums)  // ⟹ : String; (using listShow); render[List[Int]]; (using intShow)
val sorted = nums.sorted  // ⟹ : List[Int]; (using Ordering[Int]); nums.sorted[Int]
val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1)  // ⟹ : List[Tuple2[String, Int]]; (using Ordering[String]); .sortBy[String]; List.apply[Tuple2[String, Int]]; ArrowAssoc("b"); "b" ->[Int]; ArrowAssoc("a"); "a" ->[Int]
val labeled = nums.map(n => n -> render(n))  // ⟹ : List[Tuple2[Int, String]]; nums.map[Tuple2[Int, String]]; ArrowAssoc(n); n ->[String]; (using intShow); render[Int]
val total = nums.foldLeft(0)(_ + _)  // ⟹ : Int; nums.foldLeft[Int]
val ratio: Double = nums.size  // ⟹ int2double(nums.size)
val shownFive = 5.shown  // ⟹ : String; (using intShow)
val firstTwo =  // ⟹ : Option[String]
  for
    a <- nums.headOption
    b <- sorted.headOption
  yield render(a) + render(b)  // ⟹ (using intShow); render[Int]; (using intShow); render[Int]

// symbols:
//   List → scala.collection.immutable.List
//   Show → com.github.mercurievv.scalasemantic.docexamples.Show