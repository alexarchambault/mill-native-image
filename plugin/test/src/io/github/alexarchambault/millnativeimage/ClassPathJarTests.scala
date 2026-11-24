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
    NativeImage.classPathManifestValue(classPath, useUris = true).split(' ').toSeq

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
          val cpJar  = NativeImage.writeClassPathJar(cp, dir / "sub" / "classpath.jar", useUris = true)
          val cpAttr = Using.resource(new JarFile(cpJar.toIO)) { jf =>
            jf.getManifest.getMainAttributes.getValue(Attributes.Name.CLASS_PATH)
          }
          assertTrue(
            cpJar == dir / "sub" / "classpath.jar",
            cpAttr == NativeImage.classPathManifestValue(cp, useUris = true),
          ) && roundTrips(cp)
        }
      }
    },
    test("plain paths for older GraalVM versions") {
      ZIO.attemptBlocking {
        withTmpDir { dir =>
          val cp = Seq(dir / "a.jar", dir / "classes")
          assertTrue(
            NativeImage.classPathManifestValue(cp, useUris = false) ==
              cp.map(NativeImage.absPath).mkString(" ")
          )
        }
      }
    },
    suite("GraalVM version")(
      test("read from GRAALVM_VERSION") {
        def release(version: String) =
          s"""IMPLEMENTOR="GraalVM Community"
             |JAVA_RUNTIME_VERSION="25.0.5.0.3+1-jvmci-25.5-b26"
             |GRAALVM_VERSION="$version"
             |""".stripMargin
        assertTrue(
          NativeImage.graalVmVersion(release("25.5.5.0.3")) == Some((25, 5)),
          NativeImage.graalVmVersion(release("23.1.2")) == Some((23, 1)),
          NativeImage.graalVmVersion(release("25.0.2")) == Some((25, 0)),
        )
      },
      test("read from the JVMCI version as a fallback") {
        val release =
          """IMPLEMENTOR="GraalVM Community"
            |JAVA_RUNTIME_VERSION="21.0.2+13-jvmci-23.1-b30"
            |""".stripMargin
        assertTrue(
          NativeImage.graalVmVersion(release) == Some((23, 1)),
          NativeImage.graalVmVersion("JAVA_RUNTIME_VERSION=\"25.0.2+10-jvmci-b01\"").isEmpty,
          NativeImage.graalVmVersion("").isEmpty,
        )
      },
      test("URIs from GraalVM 25.5 on") {
        ZIO.attemptBlocking {
          withTmpDir { dir =>
            def usesUris(release: Option[String]) = {
              os.remove(dir / "release")
              release.foreach(os.write(dir / "release", _))
              NativeImage.manifestClassPathUsesUris(dir)
            }
            def graalVm(version: String) = Some(s"GRAALVM_VERSION=\"$version\"")
            // evaluated eagerly, as assertTrue evaluates its arguments after the temporary directory is removed
            val withUris = Seq("25.5.5.0.3", "25.6.5.1", "26.0.0")
              .filterNot(v => usesUris(graalVm(v)))
            val withoutUris =
              (Seq("25.4.4.1.1", "25.0.2", "23.1.2").map(graalVm) ++
                Seq(Some("IMPLEMENTOR=\"Other\""), None))
                .filter(usesUris)
            assertTrue(withUris.isEmpty, withoutUris.isEmpty)
          }
        }
      },
    ),
  )
}
