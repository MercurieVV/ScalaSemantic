package com.github.mercurievv.scalasemantic.docexamples

                                                                                                    
                                                                                                
                                                                                                   
                                                                                            
                                                                                               
    
trait Show[A]:
  def show(a: A): String

object Show:
                                                                               
  def apply[A](using s: Show[A]): Show[A] = s

  given intShow: Show[Int] with
    def show(a: Int) = a.toString  // ⟹ type: String

  given stringShow: Show[String] with
    def show(a: String) = a  // ⟹ type: String

                                                                                              
  given listShow[A: Show]: Show[List[A]] with  // ⟹ type: Show[A]; elaborated: (using Show[A])
    def show(a: List[A]) =  // ⟹ type: String
      a.map(Show[A].show).mkString("[", ", ", "]")  // ⟹ elaborated: Show[A](using Show[A])

                                                                                              
def render[A: Show](a: A): String = Show[A].show(a)  // ⟹ elaborated: Show[A](using Show[A])

extension (n: Int) def shown(using Show[Int]): String = render(n)  // ⟹ elaborated: render[Int](n)(using Show[Int])

object Instances:
  given doubleShow: Show[Double] with
    def show(a: Double) = a.toString  // ⟹ type: String
  given floatShow: Show[Float] with
    def show(a: Float) = a.toString  // ⟹ type: String

                                                                                                 
                                                                                                    
                                                                                                   
import Instances.given

val nums = List(1, 2, 3)  // ⟹ type: List[Int]; elaborated: List.apply[Int](1, 2, 3)
val pi = render(3.14)  // ⟹ type: String; elaborated: render[Double](3.14)(using doubleShow)
val flo = render(1.0f)  // ⟹ type: String; elaborated: render[Float](1.0f)(using floatShow)
val out = render(nums)  // ⟹ type: String; elaborated: render[List[Int]](nums)(using listShow(using intShow))
val sorted = nums.sorted  // ⟹ type: List[Int]; elaborated: nums.sorted[Int](using Ordering[Int])
val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1)  // ⟹ type: List[Tuple2[String, Int]]; elaborated: List.apply[Tuple2[String, Int]]("b" ->[Int] 2, "a" ->[Int][String] 1).sortBy(_._1)(using Ordering[String])
val labeled = nums.map(n => n -> render(n))  // ⟹ type: List[Tuple2[Int, String]]; elaborated: ArrowAssoc[Int](n); elaborated: render[Int](n)(using intShow)
val total = nums.foldLeft(0)(_ + _)  // ⟹ type: Int; nums.foldLeft[Int]
val ratio: Double = nums.size  // ⟹ elaborated: int2double(nums.size)
val shownFive = 5.shown  // ⟹ type: String; elaborated: 5.shown(using intShow)
val firstTwo =  // ⟹ type: Option[String]
  for
    a <- nums.headOption
    b <- sorted.headOption
  yield render(a) + render(b)  // ⟹ elaborated: render[Int](a)(using intShow); elaborated: render[Int](b)(using intShow)
