package com.github.mercurievv.scalasemantic.sbtplugin

import sbt.*

object CorpusFetch {

  final case class CorpusPin(ref: String, sha256: String)

  final case class VendorCorpus(
      id: String,
      owner: String,
      repo: String,
      pin: CorpusPin,
      subtree: String,
      targetName: String
  ) {
    def archiveUrl: String = s"https://github.com/$owner/$repo/archive/${pin.ref}.tar.gz"
    def archiveRoot: String = s"$repo-${pin.ref.stripPrefix("v")}"
  }

  val vendorCorpora: Seq[VendorCorpus] = Seq(
    VendorCorpus(
      id = "scalameta-semanticdb-integration",
      owner = "scalameta",
      repo = "scalameta",
      pin = CorpusPin("v4.13.9", "4617568610271364a83bba0c61e9ffc8fe77457966d1a95abd7ad7540b322146"),
      subtree = "tests-semanticdb/src/test/resources/example",
      targetName = "scala-2.13"
    ),
    VendorCorpus(
      id = "scala3-semanticdb-expect",
      owner = "scala",
      repo = "scala3",
      pin = CorpusPin("3.8.4", "c0b742a933f12c4fce53554bb067c2df176f7b22c3c16c7cd72b12d64cff4d03"),
      subtree = "tests/semanticdb/expect",
      targetName = "scala-3"
    )
  )

  def fetch(vendorDir: File, log: Logger): Seq[File] = {
    val archiveDir = vendorDir / "_archives"
    IO.createDirectory(archiveDir)
    vendorCorpora.map { corpus =>
      val archive = archiveDir / s"${corpus.id}-${corpus.pin.ref}.tar.gz"
      if (archive.exists()) {
        verifySha256(archive, corpus.pin.sha256)
        log.info(s"Using cached checksum-verified archive: $archive")
      } else {
        downloadArchive(corpus.archiveUrl, archive, log)
        verifySha256(archive, corpus.pin.sha256)
      }
      extractCorpus(archive, corpus, vendorDir, log)
    }
  }

  private def sha256Hex(file: File): String = {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val in = new java.io.BufferedInputStream(new java.io.FileInputStream(file))
    try {
      val buffer = new Array[Byte](8192)
      Iterator
        .continually(in.read(buffer))
        .takeWhile(_ != -1)
        .foreach(read => digest.update(buffer, 0, read))
    } finally in.close()
    digest.digest().map("%02x".format(_)).mkString
  }

  private def verifySha256(file: File, expected: String): Unit = {
    val actual = sha256Hex(file)
    if (!actual.equalsIgnoreCase(expected))
      sys.error(
        s"Checksum mismatch for ${file.getName}: expected $expected but found $actual. " +
          "Delete the cached archive and retry only after confirming the pinned upstream ref."
      )
  }

  private def downloadArchive(url: String, target: File, log: Logger): Unit = {
    val tmp = target.getParentFile / s".${target.getName}.download"
    IO.delete(tmp)
    log.info(s"Downloading $url")
    val connection = new java.net.URI(url).toURL.openConnection()
    connection.setConnectTimeout(15000)
    connection.setReadTimeout(120000)
    val in = new java.io.BufferedInputStream(connection.getInputStream)
    try IO.transfer(in, tmp)
    finally in.close()
    IO.move(tmp, target)
  }

  private def extractCorpus(
      archive: File,
      corpus: VendorCorpus,
      vendorDir: File,
      log: Logger
  ): File = {
    val dest = vendorDir / corpus.targetName
    val marker = dest / ".corpus-fetch.sha256"
    if (dest.exists() && marker.exists() && IO.read(marker).trim == corpus.pin.sha256) {
      log.info(s"${corpus.targetName} corpus is up to date: $dest")
      dest
    } else {
      val tmp = vendorDir / s".${corpus.targetName}.tmp"
      IO.delete(tmp)
      IO.createDirectory(tmp)
      val stripComponents = corpus.subtree.split('/').length + 1
      val sourcePath = s"${corpus.archiveRoot}/${corpus.subtree}"
      val rc = scala.sys.process.Process(
        Seq(
          "tar",
          "-xzf",
          archive.getAbsolutePath,
          "-C",
          tmp.getAbsolutePath,
          s"--strip-components=$stripComponents",
          sourcePath
        )
      ).!
      if (rc != 0) sys.error(s"Failed to extract $sourcePath from ${archive.getName} (exit $rc)")
      IO.delete(dest)
      IO.move(tmp, dest)
      IO.write(marker, corpus.pin.sha256 + "\n")
      log.info(s"Extracted ${corpus.id} (${corpus.pin.ref}) to $dest")
      dest
    }
  }
}
