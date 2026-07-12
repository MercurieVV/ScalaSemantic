package com.github.mercurievv.scalasemantic.docexamples

abstract class Processor:
  def process(x: String): String

class UpperProcessor extends Processor:
  override def process(x: String): String =
    x.toUpperCase

class ReverseProcessor extends Processor:
  override def process(x: String): String =
    x.reverse

def transform(p: Processor, input: String): String =
  p.process(input)

def compose(p1: Processor, p2: Processor, input: String): String =
  transform(p2, transform(p1, input))

def pipeline(input: String): String =
  val upper = UpperProcessor()
  val rev = ReverseProcessor()
  compose(upper, rev, input)
