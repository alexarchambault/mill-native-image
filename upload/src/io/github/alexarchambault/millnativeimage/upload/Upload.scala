package io.github.alexarchambault.millnativeimage.upload

import java.io._
import java.nio.ByteBuffer
import java.nio.charset.{MalformedInputException, StandardCharsets}
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipException, ZipFile, ZipOutputStream}

import sttp.client.quick._

import scala.util.control.NonFatal
import scala.util.Properties

object Upload {

  def platformExtension: String =
    if (Properties.isWin) ".exe"
    else ""

  def platformSuffix: String = {
    val arch = sys.props("os.arch").toLowerCase(java.util.Locale.ROOT) match {
      case "amd64" => "x86_64"
      case other => other
    }
    val os =
      if (Properties.isWin) "pc-win32"
      else if (Properties.isLinux) "pc-linux"
      else if (Properties.isMac) "apple-darwin"
      else sys.error(s"Unrecognized OS: ${sys.props("os.name")}")
    s"$arch-$os"
  }

  private def contentType(path: Path): String = {

    val isZipFile = {
      var zf: ZipFile = null
      try { zf = new ZipFile(path.toFile); true }
      catch { case _: ZipException => false }
      finally { if (zf != null) zf.close() }
    }

    lazy val isTextFile =
      try {
        StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
        true
      }
      catch { case e: MalformedInputException => false }

    if (isZipFile) "application/zip"
    else if (isTextFile) "text/plain"
    else "application/octet-stream"
  }

  private def releaseId(
    ghOrg: String,
    ghProj: String,
    ghToken: String,
    tag: String
  ): Long = {
    val url = uri"https://api.github.com/repos/$ghOrg/$ghProj/releases?access_token=$ghToken"
    val resp = quickRequest.get(url).send()

    val json = ujson.read(resp.body)
    val releaseId =
      try {
        json
          .arr
          .find(_("tag_name").str == tag)
          .map(_("id").num.toLong)
          .getOrElse {
            val tags = json.arr.map(_("tag_name").str).toVector
            sys.error(s"Tag $tag not found (found tags: ${tags.mkString(", ")}")
          }
      } catch {
        case NonFatal(e) =>
          System.err.println(resp.body)
          throw e
      }

    System.err.println(s"Release id is $releaseId")

    releaseId
  }

  private def currentAssets(
    releaseId: Long,
    ghOrg: String,
    ghProj: String,
    ghToken: String
  ): Map[String, Long] = {

    val resp = quickRequest
      .header("Accept", "application/vnd.github.v3+json")
      .header("Authorization", s"token $ghToken")
      .get(uri"https://api.github.com/repos/$ghOrg/$ghProj/releases/$releaseId/assets")
      .send()
    val json = ujson.read(resp.body)
    json
      .arr
      .iterator
      .map { obj =>
        obj("name").str -> obj("id").num.toLong
      }
      .toMap
  }

  /**
   * Uploads files as GitHub release assets.
   *
   * @param uploads List of local files / asset name to be uploaded
   * @param ghOrg GitHub organization of the release
   * @param ghProj GitHub project name of the release
   * @param ghToken GitHub token
   * @param tag Tag to upload assets to
   * @param dryRun Whether to run a dry run (printing the actions that would have been done, but not uploading anything)
   */
  def upload(
    ghOrg: String,
    ghProj: String,
    ghToken: String,
    tag: String,
    dryRun: Boolean,
    overwrite: Boolean
  )(
    uploads: (Path, String)*
  ): Unit = {

    val releaseId0 = releaseId(ghOrg, ghProj, ghToken, tag)

    val currentAssets0 = if (overwrite) currentAssets(releaseId0, ghOrg, ghProj, ghToken) else Map.empty[String, Long]

    for ((f0, name) <- uploads) {

      currentAssets0
        .get(name)
        .filter(_ => overwrite)
        .foreach { assetId =>
          val resp = quickRequest
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", s"token $ghToken")
            .delete(uri"https://api.github.com/repos/$ghOrg/$ghProj/releases/assets/$assetId")
            .send()
        }

      val uri = uri"https://uploads.github.com/repos/$ghOrg/$ghProj/releases/$releaseId0/assets?name=$name&access_token=$ghToken"
      val contentType0 = contentType(f0)
      System.err.println(s"Detected content type of $f0: $contentType0")
      if (dryRun)
        System.err.println(s"Would have uploaded $f0 as $name")
      else {
        System.err.println(s"Uploading $f0 as $name")
        quickRequest
          .body(f0)
          .header("Content-Type", contentType0)
          .post(uri)
          .send()
      }
    }
  }

  private def readInto(is: InputStream, os: OutputStream): Unit = {
    val buf = Array.ofDim[Byte](1024 * 1024)
    var read = -1
    while ({
      read = is.read(buf)
      read >= 0
    }) os.write(buf, 0, read)
  }

  private def writeInZip(name: String, file: os.Path, zip: os.Path): Unit = {
    os.makeDir.all(zip / os.up)

    var fis: InputStream = null
    var fos: FileOutputStream = null
    var zos: ZipOutputStream = null

    try {
      fis = os.read.inputStream(file)
      fos = new FileOutputStream(zip.toIO)
      zos = new ZipOutputStream(new BufferedOutputStream(fos))

      val ent = new ZipEntry(name)
      ent.setLastModifiedTime(FileTime.fromMillis(os.mtime(file)))
      ent.setSize(os.size(file))
      zos.putNextEntry(ent)
      readInto(fis, zos)
      zos.closeEntry()

      zos.finish()
    } finally {
      if (zos != null) zos.close()
      if (fos != null) fos.close()
      if (fis != null) fis.close()
    }
  }

  def copyLauncher(
    nativeLauncher: os.Path,
    directory: String,
    name: String,
    compress: Boolean,
    suffix: String = ""
  ): os.Path = {
    val path = os.Path(directory, os.pwd)
    if (Properties.isWin && compress) {
      val dest = path / s"$name-$platformSuffix$suffix.zip"
      writeInZip(s"$name$platformExtension", nativeLauncher, dest)
      dest
    } else {
      val dest = path / s"$name-$platformSuffix$suffix$platformExtension"
      os.copy(nativeLauncher, dest, createFolders = true, replaceExisting = true)
      if (compress) {
        val compressedDest = path / s"$name-$platformSuffix$suffix.gz"
        var fis: FileInputStream = null
        var fos: FileOutputStream = null
        var gzos: GZIPOutputStream = null
        try {
          fis = new FileInputStream(dest.toIO)
          fos = new FileOutputStream(compressedDest.toIO)
          gzos = new GZIPOutputStream(fos)

          readInto(fis, gzos)
          gzos.finish()
        } finally {
          if (gzos != null) gzos.close()
          if (fos != null) fos.close()
          if (fis != null) fis.close()
        }
        compressedDest
      } else {
        dest
      }
    }
  }
}
