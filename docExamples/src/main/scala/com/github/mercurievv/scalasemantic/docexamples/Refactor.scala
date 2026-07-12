package com.github.mercurievv.scalasemantic.docexamples

def calculateTotal(items: List[Int], taxRate: Double): Double =
  val subtotal = items.sum
  val tax = subtotal * taxRate
  val total = subtotal + tax
  total

def applyDiscount(price: Double, discountPercent: Double): Double =
  val discountAmount = price * (discountPercent / 100.0)
  price - discountAmount

class Invoice:
  def total: Double =
    val amount1 = calculateTotal(List(100, 200), 0.1)
    val amount2 = calculateTotal(List(50, 75), 0.1)
    amount1 + amount2

def invoiceAmount: Double =
  val inv = Invoice()
  inv.total

def discountedAmount: Double =
  val amount = invoiceAmount
  applyDiscount(amount, 10.0)
