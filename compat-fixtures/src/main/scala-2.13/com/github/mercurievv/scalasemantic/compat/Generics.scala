package com.github.mercurievv.scalasemantic.compat

/** Generic typeclass definition — used by the Implicits bundle and resolve-implicits tests. */
trait Show[A] {
  def show(a: A): String
}
