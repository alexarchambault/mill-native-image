package io.github.alexarchambault.millnativeimage

import java.net.URI
import java.nio.file.Paths
import java.util.jar.{Attributes, JarFile}
import zio.ZIO
import zio.test.*

import scala.util.Using

object ClassPathJarTests extends ZIOSpecDefault {

  def withTmpDir[T](f: os.Path => T): T = {
    val dir = os.temp.dir(prefix = "mill-native-image-tests")
    try f(dir)
    finally os.remove.all(dir)
  }

  def entries(classPath: Seq[os.Path]): Seq[String] =
    NativeImage.classPathManifestValue(classPath).split(' ').toSeq

  def roundTrips(classPath: Seq[os.Path]): TestResult = {
    val entries0 = entries(classPath)
    val resolved = entries0.map(e => os.Path(Paths.get(URI.create(e))))
    assertTrue(
      entries0.length == classPath.length,
      entries0.forall(_.startsWith("file:/")),
      resolved == classPath,
    )
  }

  def spec = suite("ClassPathJar")(
    test("entries are file URIs resolving back to the original paths") {
      ZIO.attemptBlocking {
        withTmpDir { dir =>
          val jar        = dir / "a.jar"
          val missingJar = dir / "missing.jar"
          os.write(jar, "")
          roundTrips(Seq(jar, missingJar))
        }
      }
    },
    test("paths with spaces round-trip") {
      ZIO.attemptBlocking {
        withTmpDir { dir =>
          val jar        = dir / "with space" / "a b.jar"
          val classesDir = dir / "other dir" / "classes"
          os.write(jar, "", createFolders = true)
          os.makeDir.all(classesDir)
          val entries0 = entries(Seq(jar, classesDir))
          assertTrue(!entries0.exists(_.contains(" "))) && roundTrips(Seq(jar, classesDir))
        }
      }
    },
    test("directories end with /") {
      ZIO.attemptBlocking {
        withTmpDir { dir =>
          val classesDir = dir / "classes"
          val jar        = dir / "a.jar"
          os.makeDir.all(classesDir)
          os.write(jar, "")
          val entries0 = entries(Seq(classesDir, jar))
          assertTrue(entries0.head.endsWith("/"), !entries0(1).endsWith("/")) &&
          roundTrips(Seq(classesDir, jar))
        }
      }
    },
    test("writeClassPathJar writes the Class-Path attribute") {
      ZIO.attemptBlocking {
        withTmpDir { dir =>
          val jar        = dir / "with space" / "a.jar"
          val classesDir = dir / "classes"
          os.write(jar, "", createFolders = true)
          os.makeDir.all(classesDir)
          val cp     = Seq(jar, classesDir)
          val cpJar  = NativeImage.writeClassPathJar(cp, dir / "sub" / "classpath.jar")
          val cpAttr = Using.resource(new JarFile(cpJar.toIO)) { jf =>
            jf.getManifest.getMainAttributes.getValue(Attributes.Name.CLASS_PATH)
          }
          assertTrue(
            cpJar == dir / "sub" / "classpath.jar",
            cpAttr == NativeImage.classPathManifestValue(cp),
          ) && roundTrips(cp)
        }
      }
    },
  )
}
