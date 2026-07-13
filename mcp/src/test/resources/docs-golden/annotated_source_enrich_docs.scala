package com.github.mercurievv.scalasemantic.docexamples

                                                                                                    
                                                                                                
                                                                                                   
                                                                                            
                                                                                               
    
trait Show[A]:
  def show(a: A): String

object Show:
                                                                               
  def apply[A](using s: Show[A]): Show[A] = s

  given intShow: Show[Int] with
    def show(a: Int) = a.toString  // ⟹ : String

  given stringShow: Show[String] with
    def show(a: String) = a  // ⟹ : String

                                                                                              
  given listShow[A: Show]: Show[List[A]] with  // ⟹ : Show[A]
    def show(a: List[A]) =  // ⟹ : String
      a.map(Show[A].show).mkString("[", ", ", "]")  // ⟹ a.map[String]; (using Show[A])

                                                                                              
def render[A: Show](a: A): String = Show[A].show(a)  // ⟹ (using Show[A])

extension (n: Int) def shown(using Show[Int]): String = render(n)  // ⟹ (using Show[Int]); render[Int]

val nums = List(1, 2, 3)  // ⟹ : List[Int]; List.apply[Int]
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
