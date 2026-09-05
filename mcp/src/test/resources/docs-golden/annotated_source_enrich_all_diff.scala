--- docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala (original)
+++ docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala (enriched)
@@ -14,45 +14,49 @@
   def apply[A](using s: Show[A]): Show[A] = s
 
   given intShow: Show[Int] with
     def show(a: Int) = a.toString{+  // ⟹ type: String+}
 
   given stringShow: Show[String] with
     def show(a: String) = a{+  // ⟹ type: String+}
 
   // context bound `A: Show` desugars to a `(using Show[A])` parameter the source never writes
   given listShow[A: Show]: Show[List[A]] with{+  // ⟹ type: Show[A]+}
     def show(a: List[A]) ={+  // ⟹ type: String+}
       a.map(Show[A].show).mkString("[", ", ", "]"){+  // ⟹ Show[A](using Show[A])+}
 
 // `[A: Show]` again — the using-param and the `Show[A]` summon are both invisible in the text
 def render[A: Show](a: A): String = Show[A].show(a){+  // ⟹ Show[A](using Show[A])+}
 
 extension (n: Int) def shown(using Show[Int]): String = render(n){+  // ⟹ render[Int](n)(using Show[Int])+}
 
 object Instances:
   given doubleShow: Show[Double] with
     def show(a: Double) = a.toString{+  // ⟹ type: String+}
   given floatShow: Show[Float] with
     def show(a: Float) = a.toString{+  // ⟹ type: String+}
 
 // wildcard `given` import: the givens enter scope with NO written name at the summon site — only
 // the symbols legend can say which given `render(3.14)` picked, and format=compilable explodes this
 // line to the explicit `import Instances.{doubleShow, floatShow}` of just the givens actually used
 import Instances.[-given-]{+{doubleShow, floatShow}+}
 
 val nums = List(1, 2, 3){+  // ⟹ type: List[Int]; List.apply[Int](1, 2, 3)+}
 val pi = render(3.14){+  // ⟹ type: String; render[Double](3.14)(using doubleShow)+}
 val flo = render(1.0f){+  // ⟹ type: String; render[Float](1.0f)(using floatShow)+}
 val out = render(nums){+  // ⟹ type: String; render[List[Int]](nums)(using listShow(using intShow))+}
 val sorted = nums.sorted{+  // ⟹ type: List[Int]; nums.sorted[Int](using Ordering[Int])+}
 val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1){+  // ⟹ type: List[Tuple2[String, Int]]; List.apply[Tuple2[String, Int]]("b" ->[Int] 2, "a" ->[Int] 1).sortBy[String](_._1)(using Ordering[String])+}
 val labeled = nums.map(n => n -> render(n)){+  // ⟹ type: List[Tuple2[Int, String]]; ArrowAssoc[Int](n); render[Int](n)(using intShow)+}
 val total = nums.foldLeft(0)(_ + _){+  // ⟹ type: Int+}
 val ratio: Double = nums.size{+  // ⟹ int2double(nums.size)+}
 val shownFive = 5.shown{+  // ⟹ type: String; 5.shown(using intShow)+}
 val firstTwo ={+  // ⟹ type: Option[String]+}
   for
     a <- nums.headOption
     b <- sorted.headOption
   yield render(a) + render(b){+  // ⟹ render[Int](a)(using intShow); render[Int](b)(using intShow)+}
 
+
+// symbols:
+//   List → scala.collection.immutable.List
+//   Show → com.github.mercurievv.scalasemantic.docexamples.Show