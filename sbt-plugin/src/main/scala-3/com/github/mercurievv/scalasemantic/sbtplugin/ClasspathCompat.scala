package com.github.mercurievv.scalasemantic.sbtplugin

object ClasspathCompat {
  def toAbsolutePath(ref: xsbti.VirtualFileRef, converter: xsbti.FileConverter): String =
    converter.toPath(ref).toAbsolutePath.toString
}
