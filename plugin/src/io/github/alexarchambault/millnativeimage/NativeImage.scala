package io.github.alexarchambault.millnativeimage

import java.io.File
import java.nio.charset.Charset

import mill._

import scala.util.Properties

trait NativeImage extends Module {
  import NativeImage._

  def nativeImagePersist: Boolean = false

  def nativeImageCsCommand = T{
    Seq(systemCs)
  }

  def nativeImageGraalVmJvmId = T{
    s"graalvm-java11:$defaultGraalVmVersion"
  }

  def nativeImageClassPath: T[Seq[PathRef]]
  def nativeImageMainClass: T[String]
  def nativeImageOptions = T{
    Seq.empty[String]
  }

  def nativeImageName = T{
    "launcher"
  }

  def nativeImageDockerParams = T{
    Option.empty[DockerParams]
  }
  def nativeImageDockerWorkingDir = T{
    T.dest / "working-dir"
  }

  def nativeImageUseManifest = T{
    Properties.isWin && nativeImageDockerParams().isEmpty
  }

  def nativeImageScript(imageDest: String = "") = T.command {
    val imageDestOpt = if (imageDest.isEmpty) None else Some(os.Path(imageDest, os.pwd))
    val cp = nativeImageClassPath().map(_.path)
    val mainClass0 = nativeImageMainClass()
    val nativeImageDest = {
      val dir = T.dest
      val str = dir.toString
      val idx = str.lastIndexOf("nativeImageScript")
      if (idx < 0) {
        System.err.println(s"Something went wrong, cannot find nativeImageScript in path $str")
        dir
      } else {
        val updated = str.take(idx) + "nativeImage" + str.drop(idx + "nativeImageScript".length)
        os.Path(updated)
      }
    }
    val dest = nativeImageDest / nativeImageName()
    val actualDest = nativeImageDest / (nativeImageName() + platformExtension)

    val (command, tmpDestOpt) = generateNativeImage(
      nativeImageCsCommand(),
      nativeImageGraalVmJvmId(),
      cp,
      mainClass0,
      dest,
      nativeImageOptions(),
      nativeImageDockerParams(),
      nativeImageDockerWorkingDir(),
      nativeImageUseManifest(),
      T.dest / "working-dir"
    )

    val scriptName = if (Properties.isWin) "generate.bat" else "generate.sh"
    val scriptPath = T.dest / scriptName

    def bashScript = {
      val q = "\'"
      def extra(from: os.Path, to: os.Path, move: Boolean) =
        System.lineSeparator() +
          s"mkdir -p $q${to / os.up}$q" +
          System.lineSeparator() +
          s"${if (move) "mv" else "cp"} $q$from$q $q$to$q"

      val extra0 = tmpDestOpt.fold("") { tmpDest =>
        extra(tmpDest, actualDest, move = true)
      }

      val extra1 = imageDestOpt.fold("") { imageDest =>
        extra(actualDest, imageDest, move = false)
      }

      s"""#!/usr/bin/env bash
         |set -e
         |${command.map(a => q + a.replace(q, "\\" + q) + q).mkString(" ")}
         |""".stripMargin + extra0 + extra1
    }

    def batScript = {
      val q = "\""
      def extra(from: os.Path, to: os.Path, move: Boolean) =
        System.lineSeparator() +
          s"md $q${to / os.up}$q" +
          System.lineSeparator() +
          s"${if (move) "mv" else "copy /y"} $q$from$q $q$to$q"

      val extra0 = tmpDestOpt.fold("") { tmpDest =>
        extra(tmpDest, actualDest, move = true)
      }

      val extra1 = imageDestOpt.fold("") { imageDest =>
        extra(actualDest, imageDest, move = false)
      }

      s"""@call ${command.map(a => q + a.replace(q, "\\" + q) + q).mkString(" ")}
         |""".stripMargin + extra0 + extra1
    }

    val content = if (Properties.isWin) batScript else bashScript

    os.write.over(scriptPath, content.getBytes(Charset.defaultCharset()), createFolders = true)

    if (!Properties.isWin)
      os.perms.set(scriptPath, "rwxr-xr-x")

    PathRef(scriptPath)
  }

  def writeNativeImageScript(scriptDest: String, imageDest: String) = {
    val scriptDest0 = os.Path(scriptDest, os.pwd)
    T.command {
      val script = nativeImageScript(imageDest)().path
      os.copy(script, scriptDest0, replaceExisting = true, createFolders = true)
    }
  }

  def nativeImage =
    if (nativeImagePersist)
      T.persistent {
        val cp = nativeImageClassPath().map(_.path)
        val mainClass0 = nativeImageMainClass()
        val dest = T.dest / nativeImageName()
        val actualDest = T.dest / (nativeImageName() + platformExtension)

        if (os.isFile(actualDest))
          T.log.info(s"Warning: not re-computing ${actualDest.relativeTo(os.pwd)}, delete it if you think it's stale")
        else {
          val (command, tmpDestOpt) = generateNativeImage(
            nativeImageCsCommand(),
            nativeImageGraalVmJvmId(),
            cp,
            mainClass0,
            dest,
            nativeImageOptions(),
            nativeImageDockerParams(),
            nativeImageDockerWorkingDir(),
            nativeImageUseManifest(),
            T.dest / "working-dir"
          )

          val res = os.proc(command.map(x => x: os.Shellable): _*).call(
            stdin = os.Inherit,
            stdout = os.Inherit
          )
          if (res.exitCode == 0)
            tmpDestOpt.foreach(tmpDest => os.copy(tmpDest, dest))
          else
            sys.error(s"native-image command exited with ${res.exitCode}")
        }

        PathRef(actualDest)
      }
    else
      T{
        val cp = nativeImageClassPath().map(_.path)
        val mainClass0 = nativeImageMainClass()
        val dest = T.dest / nativeImageName()
        val actualDest = T.dest / (nativeImageName() + platformExtension)

        val (command, tmpDestOpt) = generateNativeImage(
          nativeImageCsCommand(),
          nativeImageGraalVmJvmId(),
          cp,
          mainClass0,
          dest,
          nativeImageOptions(),
          nativeImageDockerParams(),
          nativeImageDockerWorkingDir(),
          nativeImageUseManifest(),
          T.dest / "working-dir"
        )

        val res = os.proc(command.map(x => x: os.Shellable): _*).call(
          stdin = os.Inherit,
          stdout = os.Inherit
        )
        if (res.exitCode == 0)
          tmpDestOpt.foreach(tmpDest => os.copy(tmpDest, dest))
        else
          sys.error(s"native-image command exited with ${res.exitCode}")

        PathRef(actualDest)
      }

}

object NativeImage {

  def defaultGraalVmVersion = "21.2.0"

  def defaultLinuxStaticDockerImage = "messense/rust-musl-cross@sha256:12d0dd535ef7364bf49cb2608ae7eaf60e40d07834eb4d9160c592422a08d3b3"
  def csLinuxX86_64Url(version: String) = s"https://github.com/coursier/coursier/releases/download/v$version/cs-x86_64-pc-linux"

  def linuxStaticParams(dockerImage: String, csUrl: String): DockerParams =
    DockerParams(
      imageName = dockerImage,
      prepareCommand = """(cd /usr/local/musl/bin && ln -s *-musl-gcc musl-gcc) && export PATH="/usr/local/musl/bin:$PATH"""",
      csUrl = csUrl,
      extraNativeImageArgs = Seq(
        "--static",
        "--libc=musl"
      )
    )
  def linuxStaticParams(): DockerParams =
    linuxStaticParams(defaultLinuxStaticDockerImage, csLinuxX86_64Url("2.0.16"))

  def defaultLinuxMostlyStaticDockerImage = "ubuntu:18.04"
  def linuxMostlyStaticParams(dockerImage: String, csUrl: String): DockerParams =
    DockerParams(
      imageName = dockerImage,
      prepareCommand = "apt-get update -q -y && apt-get install -q -y build-essential libz-dev",
      csUrl = csUrl,
      extraNativeImageArgs = Seq(
        "-H:+StaticExecutableWithDynamicLibC"
      )
    )
  def linuxMostlyStaticParams(): DockerParams =
    linuxMostlyStaticParams(defaultLinuxMostlyStaticDockerImage, csLinuxX86_64Url("2.0.16"))


  lazy val systemCs: String =
    if (Properties.isWin) {
      val pathExt = Option(System.getenv("PATHEXT"))
        .toSeq
        .flatMap(_.split(File.pathSeparator).toSeq)
      val path = Option(System.getenv("PATH"))
        .toSeq
        .flatMap(_.split(File.pathSeparator))
        .map(new File(_))

      def candidates =
        for {
          dir <- path.iterator
          ext <- pathExt.iterator
        } yield new File(dir, s"cs$ext")

      candidates
        .filter(_.canExecute)
        .toStream
        .headOption
        .map(_.getAbsolutePath)
        .getOrElse {
          System.err.println("Warning: cs not found in PATH")
          "cs"
        }
    }
    else
      "cs"

  def platformExtension: String =
    if (Properties.isWin) ".exe"
    else ""

  // should be the default index in the upcoming coursier release (> 2.0.16)
  def jvmIndex = "https://github.com/coursier/jvm-index/raw/master/index.json"

  private def vcVersions = Seq("2022", "2019", "2017")
  private def vcEditions = Seq("Enterprise", "Community", "BuildTools")
  lazy val vcvarsCandidates = Option(System.getenv("VCVARSALL")) ++ {
    for {
      isX86 <- Seq(false, true)
      version <- vcVersions
      edition <- vcEditions
    } yield {
      val programFiles = if (isX86) "Program Files (x86)" else "Program Files"
      """C:\""" + programFiles + """\Microsoft Visual Studio\""" + version + "\\" + edition + """\VC\Auxiliary\Build\vcvars64.bat"""
    }
  }

  def vcvarsOpt: Option[os.Path] =
    vcvarsCandidates
      .iterator
      .map(os.Path(_, os.pwd))
      .filter(os.exists(_))
      .toStream
      .headOption

  final case class DockerParams(
    imageName: String,
    prepareCommand: String,
    csUrl: String,
    extraNativeImageArgs: Seq[String]
  )

  object DockerParams {
    implicit val codec: upickle.default.ReadWriter[DockerParams] = upickle.default.macroRW[DockerParams]
  }

  def generateNativeImage(
    csCommand: Seq[String],
    jvmId: String,
    classPath: Seq[os.Path],
    mainClass: String,
    dest: os.Path,
    nativeImageOptions: Seq[String],
    dockerParamsOpt: Option[DockerParams],
    dockerWorkingDir: os.Path,
    createManifest: Boolean,
    workingDir: os.Path
  ): (Seq[String], Option[os.Path]) = {

    val graalVmHome = Option(System.getenv("GRAALVM_HOME")).getOrElse {
      import sys.process._
      (csCommand ++ Seq("java-home", "--jvm", jvmId, "--jvm-index", jvmIndex, "--update", "--ttl", "0")).!!.trim
    }

    val ext = if (Properties.isWin) ".cmd" else ""
    val nativeImage = s"$graalVmHome/bin/native-image$ext"

    if (!os.isFile(os.Path(nativeImage))) {
      val ret = os.proc(s"$graalVmHome/bin/gu$ext", "install", "native-image").call(
        stdin = os.Inherit,
        stdout = os.Inherit
      )
      if (ret.exitCode != 0)
        System.err.println(s"Warning: 'gu install native-image' exited with return code ${ret.exitCode}}")
      if (!os.isFile(os.Path(nativeImage)))
        System.err.println(s"Warning: $nativeImage not found, and not installed by 'gu install native-image'")
    }

    val finalCp =
      if (createManifest) {
        import java.util.jar._
        val manifest = new Manifest
        val attributes = manifest.getMainAttributes
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
        attributes.put(Attributes.Name.CLASS_PATH, classPath.map(_.toIO.getAbsolutePath).mkString(" "))
        val jarFile = File.createTempFile("classpathJar", ".jar")
        val jos = new JarOutputStream(new java.io.FileOutputStream(jarFile), manifest)
        jos.close()
        jarFile.getAbsolutePath
      } else
        classPath.map(_.toString).mkString(File.pathSeparator)

    def command(nativeImage: String, extraNativeImageArgs: Seq[String], destDir: Option[String], destName: String, classPath: String) = {
      val destDirOptions = destDir.toList.map(d => s"-H:Path=$d")
      Seq(nativeImage) ++
      extraNativeImageArgs ++
      nativeImageOptions ++
      destDirOptions ++
      Seq(
        s"-H:Name=$destName",
        "-cp",
        classPath,
        mainClass
      )
    }

    def defaultCommand = {
      val relDest = dest.relativeTo(os.pwd)
      val destDirOpt = if (relDest.segments.length > 1) Some((relDest / os.up).toString) else None
      val destName = relDest.last
      command(nativeImage, Nil, destDirOpt, destName, finalCp)
    }

    val (finalCommand, tmpDestOpt) =
      if (Properties.isWin)
        vcvarsOpt match {
          case None =>
            System.err.println(s"Warning: vcvarsall script not found in predefined locations:")
            for (loc <- vcvarsCandidates)
              System.err.println(s"  $loc")
            (defaultCommand, None)
          case Some(vcvars) =>
            // chcp 437 sometimes needed, see https://github.com/oracle/graal/issues/2522
            val escapedCommand = defaultCommand.map {
              case s if s.contains(" ") => "\"" + s + "\""
              case s => s
            }
            val script =
             s"""chcp 437
                |@call "$vcvars"
                |if %errorlevel% neq 0 exit /b %errorlevel%
                |@call ${escapedCommand.mkString(" ")}
                |""".stripMargin
            val scriptPath = workingDir / "run-native-image.bat"
            os.write.over(scriptPath, script.getBytes, createFolders = true)
            (Seq("cmd", "/c", scriptPath.toString), None)
        }
      else
        dockerParamsOpt match {
          case Some(params) =>
            var entries = Set.empty[String]
            val cpDir = dockerWorkingDir / "cp"
            if (os.exists(cpDir))
              os.remove.all(cpDir)
            os.makeDir.all(cpDir)
            val copiedCp = classPath.filter(os.exists(_)).map { f =>
              val name =
                if (entries(f.last)) {
                  var i = 1
                  val (base, ext) = if (f.last.endsWith(".jar")) (f.last.stripSuffix(".jar"), ".jar") else (f.last, "")
                  var candidate = ""
                  while ({
                    candidate = s"$base-$i$ext"
                    entries(candidate)
                  }) {
                    i += 1
                  }
                  candidate
                }
                else f.last
              entries = entries + name
              val dest = cpDir / name
              os.copy(f, dest)
              s"/data/cp/$name"
            }
            val cp = copiedCp.mkString(File.pathSeparator)
            val escapedCommand = command("native-image", params.extraNativeImageArgs, Some("/data"), "output", cp).map {
              case s if s.contains(" ") => "\"" + s + "\""
              case s => s
            }
            val backTick = "\\"
            val script =
              s"""#!/usr/bin/env bash
                 |set -e
                 |${params.prepareCommand}
                 |eval "$$(/data/cs java --env --jvm "$jvmId" --jvm-index "$jvmIndex")"
                 |gu install native-image
                 |${escapedCommand.head}""".stripMargin + escapedCommand.drop(1).map("\\\n  " + _).mkString + "\n"
            val scriptPath = dockerWorkingDir / "run-native-image.sh"
            os.write.over(scriptPath, script, createFolders = true)
            os.perms.set(scriptPath, "rwxr-xr-x")
            val csPath = os.Path(os.proc(csCommand, "get", params.csUrl).call().out.text.trim)
            if (csPath.last.endsWith(".gz")) {
              os.copy.over(csPath, dockerWorkingDir / "cs.gz")
              os.proc("gzip", "-d", dockerWorkingDir / "cs.gz").call(stdout = os.Inherit)
            }
            else
              os.copy.over(csPath, dockerWorkingDir / "cs")
            os.perms.set(dockerWorkingDir / "cs", "rwxr-xr-x")
            val termOpt = if (System.console() == null) Nil else Seq("-t")
            val dockerCmd = Seq("docker", "run") ++ termOpt ++ Seq(
              "--rm",
              "-v", s"$dockerWorkingDir:/data",
              "-e", "COURSIER_JVM_CACHE=/data/jvm-cache",
              "-e", "COURSIER_CACHE=/data/cs-cache",
              params.imageName,
              "/data/run-native-image.sh"
            )
            (dockerCmd, Some(dockerWorkingDir / "output"))
          case None =>
            (defaultCommand, None)
        }

    (finalCommand, tmpDestOpt)
  }

}
