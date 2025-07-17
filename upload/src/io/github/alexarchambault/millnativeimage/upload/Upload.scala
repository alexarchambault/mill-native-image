package io.github.alexarchambault.millnativeimage.upload

import sttp.client4.quick.*

import java.io.*
import java.math.BigInteger
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipException, ZipFile, ZipOutputStream}
import scala.util.Properties
import scala.util.control.NonFatal

object Upload {
  def platformExtension: String = if Properties.isWin then ".exe" else ""

  def platformSuffix: String = {
    val arch = sys.props("os.arch").toLowerCase(java.util.Locale.ROOT) match {
      case "amd64" => "x86_64"
      case other   => other
    }
    val os =
      if Properties.isWin then "pc-win32"
      else if Properties.isLinux then "pc-linux"
      else if Properties.isMac then "apple-darwin"
      else sys.error(s"Unrecognized OS: ${sys.props("os.name")}")
    s"$arch-$os"
  }

  private def contentType(path: os.Path): String = {

    val isZipFile = {
      var zf: ZipFile = null
      try { zf = new ZipFile(path.toIO); true }
      catch { case _: ZipException => false }
      finally { if zf != null then zf.close() }
    }

    if isZipFile then "application/zip" else "application/octet-stream"
  }

  private def releaseId(
    ghOrg:   String,
    ghProj:  String,
    ghToken: String,
    tag:     String,
  ): Long = {
    val url  = uri"https://api.github.com/repos/$ghOrg/$ghProj/releases"
    val resp = quickRequest
      .header("Authorization", s"token $ghToken")
      .get(url)
      .send()

    val json      = ujson.read(resp.body)
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
    ghOrg:     String,
    ghProj:    String,
    ghToken:   String,
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
   * @param uploads
   *   List of local files / asset name to be uploaded
   * @param ghOrg
   *   GitHub organization of the release
   * @param ghProj
   *   GitHub project name of the release
   * @param ghToken
   *   GitHub token
   * @param tag
   *   Tag to upload assets to
   * @param dryRun
   *   Whether to run a dry run (printing the actions that would have been done,
   *   but not uploading anything)
   */
  def upload(
    ghOrg:         String,
    ghProj:        String,
    ghToken:       String,
    tag:           String,
    dryRun:        Boolean,
    overwrite:     Boolean,
    printChecksum: Boolean = true,
  )(uploads:       (os.Path, String)*
  ): Unit = {
    val releaseId0 = releaseId(ghOrg, ghProj, ghToken, tag)

    val currentAssets0 =
      if overwrite then currentAssets(releaseId0, ghOrg, ghProj, ghToken) else Map.empty[String, Long]

    val extraAssets = currentAssets0.keySet.filterNot(uploads.map(_._2).toSet).toVector.sorted

    if extraAssets.nonEmpty then {
      System.err.println(s"Warning: found ${extraAssets.length} extra asset(s) that will not be overwritten:")
      for (assetName <- extraAssets)
        System.err.println(s"  $assetName")
    }

    for ((f0, name) <- uploads) {
      if printChecksum then printChecksums(f0)

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

      val uri          = uri"https://uploads.github.com/repos/$ghOrg/$ghProj/releases/$releaseId0/assets?name=$name"
      val contentType0 = contentType(f0)
      System.err.println(s"Detected content type of $f0: $contentType0")
      if dryRun then System.err.println(s"Would have uploaded $f0 as $name")
      else {
        System.err.println(s"Uploading $f0 as $name")
        quickRequest
          .body(f0.toNIO)
          .header("Authorization", s"token $ghToken")
          .header("Content-Type", contentType0)
          .post(uri)
          .send()
      }
    }
  }

  private def checksum(is: InputStream, alg: String, len: Int): String = {
    val md = MessageDigest.getInstance(alg)

    val b    = Array.ofDim[Byte](16 * 1024)
    var read = -1
    while ({
      read = is.read(b)
      read >= 0
    })
      if read > 0 then md.update(b)

    val digest = md.digest()
    val res    = new BigInteger(1, digest).toString(16)
    if res.length < len then ("0" * (len - res.length)) + res
    else res
  }

  private def checksum(f: os.Path, alg: String, len: Int): String = {
    var is: InputStream = null
    try {
      is = os.read.inputStream(f)
      checksum(is, alg, len)
    } finally {
      if is != null then is.close()
    }
  }

  private def sha256(f: os.Path): String = checksum(f, "SHA-256", 64)
  private def sha1(f:   os.Path): String = checksum(f, "SHA-1", 40)

  private def printChecksums(f: os.Path): Unit = {
    System.err.println(f.toString)
    val sha1Value = sha1(f)
    System.err.println(s"    SHA-1: $sha1Value")
    val sha256Value = sha256(f)
    System.err.println(s"  SHA-256: $sha256Value")
  }

  private def readInto(is: InputStream, os: OutputStream): Unit = {
    val buf  = Array.ofDim[Byte](1024 * 1024)
    var read = -1
    while ({
      read = is.read(buf)
      read >= 0
    }) os.write(buf, 0, read)
  }

  private def writeInZip(name: String, file: os.Path, zip: os.Path): Unit = {
    os.makeDir.all(zip / os.up)

    var fis: InputStream      = null
    var fos: FileOutputStream = null
    var zos: ZipOutputStream  = null

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
      if zos != null then zos.close()
      if fos != null then fos.close()
      if fis != null then fis.close()
    }
  }

  @deprecated("Use copyLauncher0 instead", "0.1.31")
  def copyLauncher(
    nativeLauncher: os.Path,
    directory:      String,
    name:           String,
    compress:       Boolean,
    suffix:         String = "",
    printChecksums: Boolean = true,
    wd:             os.Path = os.pwd, // issue with newer Mill versions, where this isn't the workspace
  ): os.Path =
    copyLauncher0(
      nativeLauncher,
      directory,
      name,
      compress,
      wd,
      suffix,
      printChecksums,
    )

  def copyLauncher0(
    nativeLauncher: os.Path,
    directory:      String,
    name:           String,
    compress:       Boolean,
    workspace:      os.Path,
    suffix:         String = "",
    printChecksums: Boolean = true,
  ): os.Path = {
    if printChecksums then Upload.printChecksums(nativeLauncher)

    val path = os.Path(directory, workspace)
    val dest =
      if Properties.isWin && compress then {
        val dest0 = path / s"$name-$platformSuffix$suffix.zip"
        writeInZip(s"$name$platformExtension", nativeLauncher, dest0)
        dest0
      } else {
        val dest0 = path / s"$name-$platformSuffix$suffix$platformExtension"
        os.copy(nativeLauncher, dest0, createFolders = true, replaceExisting = true)
        if compress then {
          val compressedDest = path / s"$name-$platformSuffix$suffix.gz"
          var fis:  FileInputStream  = null
          var fos:  FileOutputStream = null
          var gzos: GZIPOutputStream = null
          try {
            fis = new FileInputStream(dest0.toIO)
            fos = new FileOutputStream(compressedDest.toIO)
            gzos = new GZIPOutputStream(fos)

            readInto(fis, gzos)
            gzos.finish()
          } finally {
            if gzos != null then gzos.close()
            if fos != null then fos.close()
            if fis != null then fis.close()
          }

          os.remove(dest0)
          compressedDest
        } else dest0
      }

    if printChecksums then Upload.printChecksums(dest)

    dest
  }
}
