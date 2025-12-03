//| mvnDeps:
//| - com.lumidion::sonatype-central-client-requests:0.6.0
import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill.*
import mill.api.{Task, *}
import mill.javalib.api.*
import scalalib.*
import publish.*
import mill.util.{Tasks, VcsVersion}

import scala.util.Try

val millVersions = Seq("1.0.0") // scala-steward:off
val millBinaryVersions:                     Seq[String] = millVersions.map(millBinaryVersion)
def millBinaryVersion(millVersion: String): String      = {
  val nativeBinaryVersion = JvmWorkerUtil.scalaNativeBinaryVersion(millVersion)
  nativeBinaryVersion.split("\\.") match {
    case Array(major, minor, _*) if Try(major.toInt < 1).getOrElse(false) => s"$major.$minor"
    case Array(major, minor, _*)                                          => major
  }
}

def millVersion(binaryVersion: String): String = millVersions.find(v => millBinaryVersion(v) == binaryVersion).get

def ghOrg      = "alexarchambault"
def ghName     = "mill-native-image"
def publishOrg = "io.github.alexarchambault.mill"
trait MillNativeImagePublishModule extends SonatypeCentralPublishModule {
  def pomSettings: T[PomSettings] = PomSettings(
    description = artifactName(),
    organization = publishOrg,
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("alexarchambault", "mill-native-image"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    ),
  )
  def publishVersion: T[String] = Task {
    val state = VcsVersion.vcsState()
    if state.commitsSinceLastTag > 0 then {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .map(_.stripPrefix("v"))
        .flatMap { tag =>
          val idx = tag.lastIndexOf(".")
          if idx >= 0 then
            Some(tag.take(idx + 1) + (tag.drop(idx + 1).takeWhile(_.isDigit).toInt + 1).toString + "-SNAPSHOT")
          else None
        }
        .getOrElse("0.0.1-SNAPSHOT")
      Some(versionOrEmpty)
        .filter(_.nonEmpty)
        .getOrElse(state.format())
    } else
      state
        .lastTag
        .getOrElse(state.format())
        .stripPrefix("v")
  }
}

object Scala {
  def version3: String = "3.7.1"
}

object plugin      extends Cross[PluginModule](millBinaryVersions)
trait PluginModule extends Cross.Module[String] with ScalaModule with MillNativeImagePublishModule {
  def millBinaryVersion: String      = crossValue
  def artifactName:      T[String]   = s"mill-native-image_mill$millBinaryVersion"
  def scalaVersion:      T[String]   = Scala.version3
  def compileMvnDeps:    T[Seq[Dep]] = super.compileMvnDeps() ++ Seq(
    mvn"com.lihaoyi::mill-libs-scalalib:${millVersion(millBinaryVersion)}"
  )
}

object upload      extends UploadModule
trait UploadModule extends ScalaModule with MillNativeImagePublishModule {
  override def artifactName:   T[String]   = "mill-native-image-upload"
  def scalaVersion:            T[String]   = Scala.version3
  override def compileMvnDeps: T[Seq[Dep]] =
    super.compileMvnDeps() ++ Seq(
      mvn"com.lihaoyi::os-lib:0.11.6",
      mvn"com.lihaoyi::ujson:4.4.1",
    )

  override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
    mvn"com.softwaremill.sttp.client4::core:4.0.9"
  )
}

def publishSonatype(tasks: Tasks[PublishModule.PublishData]): Task.Command[Unit] =
  Task.Command {
    // TODO: include version string in the bundle name (shouldn't influence releases)
    val bundleName = s"$publishOrg-$ghName"
    System.err.println(s"Publishing bundle: $bundleName")

    import scala.concurrent.duration._

    val data = Task.sequence(tasks.value)()
    val log  = Task.ctx().log

    val credentials = SonatypeCredentials(
      username = sys.env("SONATYPE_USERNAME"),
      password = sys.env("SONATYPE_PASSWORD"),
    )
    val pgpPassword = sys.env("PGP_PASSPHRASE")
    val timeout     = 10.minutes

    System.err.println("Actual artifacts included in the bundle:")
    val artifacts = data.map {
      case PublishModule.PublishData(a, s) =>
        System.err.println(s"  ${a.group}:${a.id}:${a.version}")
        (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set      = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(
        set.size == 1,
        s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}",
      )
      set.head
    }
    System.err.println(s"Is release: $isRelease")
    val publisher = new SonatypeCentralPublisher(
      credentials = credentials,
      gpgArgs = Seq(
        "--detach-sign",
        "--batch=true",
        "--yes",
        "--pinentry-mode",
        "loopback",
        "--passphrase",
        pgpPassword,
        "--armor",
        "--use-agent",
      ),
      readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
      log = log,
      workspace = BuildCtx.workspaceRoot,
      env = sys.env,
      awaitTimeout = timeout.toMillis.toInt,
    )
    val publishingType = if (isRelease) PublishingType.AUTOMATIC else PublishingType.USER_MANAGED
    System.err.println(s"Publishing type: $publishingType")
    val finalBundleName = if (bundleName.nonEmpty) Some(bundleName) else None
    System.err.println(s"Final bundle name: $finalBundleName")
    publisher.publishAll(
      publishingType = publishingType,
      singleBundleName = finalBundleName,
      artifacts = artifacts*,
    )
  }
