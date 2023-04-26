import mill._, mill.scalalib._, mill.scalalib.scalafmt._
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.23`
import io.github.alexarchambault.millnativeimage.NativeImage

object hello extends ScalaModule with NativeImage {
  def scalaVersion = "3.3.0-RC4"
  def ivyDeps = Agg(
      ivy"dev.zio::zio:2.0.13",
      ivy"dev.zio::zio-http:3.0.0-RC1",
    )
  def nativeImageName         = "hello"
  def nativeImageMainClass    = "com.domain.Main.MainApp"
  def nativeImageClassPath    = runClasspath()
  def nativeImageGraalVmJvmId = "graalvm-java17:22.3.1"
  // GraalVM parameters needed by ZIO and ZIO-http
  def nativeImageOptions = Seq(
    "--no-fallback",
    "--enable-http",
    "--enable-url-protocols=http,https",
    "--install-exit-handlers",
    "-Djdk.http.auth.tunneling.disabledSchemes=",
    "--initialize-at-run-time=io.netty.channel.DefaultFileRegion",
    "--initialize-at-run-time=io.netty.channel.epoll.Native",
    "--initialize-at-run-time=io.netty.channel.epoll.Epoll",
    "--initialize-at-run-time=io.netty.channel.epoll.EpollEventLoop",
    "--initialize-at-run-time=io.netty.channel.epoll.EpollEventArray",
    "--initialize-at-run-time=io.netty.channel.kqueue.KQueue",
    "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventLoop",
    "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventArray",
    "--initialize-at-run-time=io.netty.channel.kqueue.Native",
    "--initialize-at-run-time=io.netty.channel.unix.Limits",
    "--initialize-at-run-time=io.netty.channel.unix.Errors",
    "--initialize-at-run-time=io.netty.channel.unix.IovArray",
    "--initialize-at-run-time=io.netty.handler.ssl.BouncyCastleAlpnSslUtils",
    "--initialize-at-run-time=io.netty.handler.codec.compression.ZstdOptions",
    "--initialize-at-run-time=io.netty.incubator.channel.uring.Native",
    "--initialize-at-run-time=io.netty.incubator.channel.uring.IOUring",
    "--initialize-at-run-time=io.netty.incubator.channel.uring.IOUringEventLoopGroup",
  ) ++ (if (sys.props.get("os.name").contains("Linux")) Seq("--static") else Seq.empty)

  // If instead of creating a Native-Image binary for current host (Eg. MacOS)
  // you want to create a Docker image with the binary for Linux in a Docker container
  // you can use the following parameters and run `DOCKER_NATIVEIMAGE=1 mill hello.nativeImage`
  def isDockerBuild = T.input(T.ctx.env.get("DOCKER_NATIVEIMAGE") != None)
  def nativeImageDockerParams = T {
    if (isDockerBuild()) {
      Some(
        NativeImage.DockerParams(
          imageName = "ubuntu:22.04",
          prepareCommand = """apt-get update -q -y &&\
                             |apt-get install -q -y build-essential libz-dev locales --no-install-recommends
                             |locale-gen en_US.UTF-8
                             |export LANG=en_US.UTF-8
                             |export LANGUAGE=en_US:en
                             |export LC_ALL=en_US.UTF-8""".stripMargin,
          csUrl = s"https://github.com/coursier/coursier/releases/download/v2.1.2/cs-x86_64-pc-linux.gz",
          extraNativeImageArgs = Nil,
        ),
      )
    } else { Option.empty[NativeImage.DockerParams] }
  }

  object test extends Tests {
    def ivyDeps = Agg(
      ivy"dev.zio::zio-test:2.0.13",
      ivy"dev.zio::zio-test-sbt:2.0.13",
    )
    def testFramework = T("zio.test.sbt.ZTestFramework")
  }
}
