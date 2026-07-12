package com.github.mercurievv.scalasemantic.docexamples

def processUserData(name: String, email: String): String =
  val trimmedName = name.trim
  val lowercaseName = trimmedName.toLowerCase
  val upperName = lowercaseName.toUpperCase
  val finalName = upperName + email
  finalName

def processProductData(title: String, category: String): String =
  val trimmedTitle = title.trim
  val lowercaseTitle = trimmedTitle.toLowerCase
  val upperTitle = lowercaseTitle.toUpperCase
  val finalTitle = upperTitle + category
  finalTitle
