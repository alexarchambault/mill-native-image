import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import de.tobiasroeser.mill.vcs.version._
import mill._
import mill.scalalib.api._
import scalalib._
import publish._

val millVersions       = Seq("0.12.0") // scala-steward:off
val millBinaryVersions = millVersions.map(millBinaryVersion)

def millBinaryVersion(millVersion: String) = JvmWorkerUtil.scalaNativeBinaryVersion(millVersion)
def millVersion(binaryVersion:     String) = millVersions.find(v => millBinaryVersion(v) == binaryVersion).get

def ghOrg      = "alexarchambault"
def ghName     = "mill-native-image"
def publishOrg = "io.github.alexarchambault.mill"
trait MillNativeImagePublishModule extends SonatypeCentralPublishModule {
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = publishOrg,
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("alexarchambault", "mill-native-image"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    ),
  )
  def publishVersion = Task {
    val state = VcsVersion.vcsState()
    if (state.commitsSinceLastTag > 0) {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .map(_.stripPrefix("v"))
        .flatMap { tag =>
          val idx = tag.lastIndexOf(".")
          if (idx >= 0)
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
  def version  = "2.13.16"
  def version3 = "3.3.6"
}

object plugin      extends Cross[PluginModule](millBinaryVersions)
trait PluginModule extends Cross.Module[String] with ScalaModule with MillNativeImagePublishModule {
  def millBinaryVersion: String = crossValue
  def artifactName   = s"mill-native-image_mill$millBinaryVersion"
  def scalaVersion   = Scala.version
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    ivy"com.lihaoyi::mill-scalalib:${millVersion(millBinaryVersion)}"
  )
}

object upload      extends Cross[UploadModule](Scala.version, Scala.version3)
trait UploadModule extends CrossScalaModule with MillNativeImagePublishModule {
  def artifactName   = "mill-native-image-upload"
  def compileIvyDeps = super.compileIvyDeps() ++ Agg(
    ivy"com.lihaoyi::os-lib:0.11.4",
    ivy"com.lihaoyi::ujson:4.2.1",
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.softwaremill.sttp.client4::core:4.0.9"
  )
}

def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
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
      workspace = Task.workspace,
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
      artifacts = artifacts: _*,
    )
  }
