package com.github.mercurievv.scalasemantic.sbtplugin

import java.io.File

object ClasspathCompat {
  def toAbsolutePath(file: File, converter: xsbti.FileConverter): String =
    file.toPath.toAbsolutePath.toString
}
