import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.3.0`

import de.tobiasroeser.mill.vcs.version._
import mill._, scalalib._, publish._

val millVersions = Seq("0.9.12", "0.10.12")
val millBinaryVersions = millVersions.map(millBinaryVersion)

def millBinaryVersion(millVersion: String) =
  millVersion.split('.').take(2).mkString(".")
def millVersion(binaryVersion: String) =
  millVersions.find(v => millBinaryVersion(v) == binaryVersion).get

trait MillNativeImagePublishModule extends PublishModule {
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "io.github.alexarchambault.mill",
    url = s"https://github.com/alexarchambault/mill-native-image",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("alexarchambault", "mill-native-image"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault","https://github.com/alexarchambault")
    )
  )
  def publishVersion = T{
    val state = VcsVersion.vcsState()
    if (state.commitsSinceLastTag > 0) {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .map(_.stripPrefix("v"))
        .flatMap { tag =>
          val idx = tag.lastIndexOf(".")
          if (idx >= 0) Some(tag.take(idx + 1) + (tag.drop(idx + 1).takeWhile(_.isDigit).toInt + 1).toString + "-SNAPSHOT")
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
  def version = "2.13.6"
}

object plugin extends Cross[PluginModule](millBinaryVersions: _*)
class PluginModule(millBinaryVersion: String)
    extends ScalaModule
    with MillNativeImagePublishModule {
  def artifactName = s"mill-native-image_mill$millBinaryVersion"
  def millSourcePath = super.millSourcePath / os.up
  def scalaVersion = Scala.version
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.lihaoyi::mill-scalalib:${millVersion(millBinaryVersion)}"
  )
}

object upload extends ScalaModule with MillNativeImagePublishModule {
  def artifactName = "mill-native-image-upload"
  def scalaVersion = Scala.version
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.lihaoyi::os-lib:0.9.1", // beware, not binary compatible with 0.7.x
    ivy"com.lihaoyi::ujson:1.4.4",
    ivy"com.softwaremill.sttp.client::core:2.2.9"
  )
}

def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
  T.command {
    import scala.concurrent.duration._

    val data = T.sequence(tasks.value)()
    val log = T.ctx().log

    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSPHRASE")
    val timeout = 10.minutes

    val artifacts = data.map {
      case PublishModule.PublishData(a, s) =>
        (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(set.size == 1, s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}")
      set.head
    }
    val publisher = new scalalib.publish.SonatypePublisher(
                 uri = "https://oss.sonatype.org/service/local",
         snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots",
         credentials = credentials,
              signed = true,
             gpgArgs = Seq("--detach-sign", "--batch=true", "--yes", "--pinentry-mode", "loopback", "--passphrase", pgpPassword, "--armor", "--use-agent"),
         readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
                 log = log,
        awaitTimeout = timeout.toMillis.toInt,
      stagingRelease = isRelease
    )

    publisher.publishAll(isRelease, artifacts: _*)
  }
