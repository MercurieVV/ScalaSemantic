   28  def render[A: Show](a: A): String = Show[A].show(a)
   29  