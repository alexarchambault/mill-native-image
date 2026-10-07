package io.github.alexarchambault.millnativeimage

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import zio.{durationInt, ZIO}
import zio.test.*

object NativeImageTests extends ZIOSpecDefault {

  /**
   * GraalVM distributions corresponding to Java LTS versions (only specifying
   * the major version picks their latest patch version)
   */
  val graalVmJvmIds: Seq[String] = Seq(
    "graalvm-community:17",
    "graalvm-community:21",
    "graalvm-community:25",
  )

  /**
   * Class path of the application we build native images of (see `test-app` in
   * the build)
   */
  def testAppClassPath: Seq[os.Path] =
    Seq(os.Path(sys.env("MILL_NATIVE_IMAGE_TEST_APP")))

  object TestBuild extends TestRootModule {
    object app      extends Cross[AppModule](graalVmJvmIds)
    trait AppModule extends Cross.Module[String] with NativeImage {
      override def nativeImageGraalVmJvmId: T[String]       = crossValue
      def nativeImageClassPath:             T[Seq[PathRef]] = Task(testAppClassPath.map(PathRef(_)))
      def nativeImageMainClass:             T[String]       = "millnativeimage.testapp.Main"
      override def nativeImageName:         T[String]       = "test-app"
    }

    lazy val millDiscover = Discover[this.type]
  }

  def withTester[T](f: UnitTester => T): T =
    UnitTester(
      TestBuild,
      sourceRoot = null,
      // GRAALVM_HOME takes precedence over nativeImageGraalVmJvmId, ignore it
      env = sys.env - "GRAALVM_HOME",
    ).scoped(f)

  def runImage(image: os.Path): Seq[String] =
    os.proc(image).call().out.lines()

  def checkOutput(output: Seq[String], jvmId: String): TestResult = {
    val javaMajor = jvmId.stripPrefix("graalvm-community:")
    assertTrue(
      output.headOption.contains("Hello from mill-native-image"),
      output.exists(_.startsWith(s"java.version=$javaMajor.")),
      output.contains("imagecode=runtime"),
    )
  }

  def nativeImageTest(jvmId: String) =
    test("nativeImage") {
      ZIO.attemptBlocking {
        withTester { eval =>
          val res   = eval(TestBuild.app(jvmId).nativeImage).fold(f => sys.error(f.toString), identity)
          val image = res.value.path
          assertTrue(image.last == "test-app" + NativeImage.platformExtension) &&
          checkOutput(runImage(image), jvmId)
        }
      }
    }

  def nativeImageScriptTest(jvmId: String) =
    test("nativeImageScript") {
      ZIO.attemptBlocking {
        withTester { eval =>
          val imageDest = TestBuild.moduleDir / "image" / ("test-app" + NativeImage.platformExtension)
          val res       = eval(TestBuild.app(jvmId).nativeImageScript(imageDest.toString))
            .fold(f => sys.error(f.toString), identity)
          val script = res.value.path
          os.proc(script).call(stdin = os.Inherit, stdout = os.Inherit, cwd = TestBuild.moduleDir)
          checkOutput(runImage(imageDest), jvmId)
        }
      }
    }

  def spec = suite("NativeImage")(
    graalVmJvmIds.map { jvmId =>
      suite(jvmId)(
        nativeImageTest(jvmId),
        nativeImageScriptTest(jvmId),
      )
    }*
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.minutes)
}
