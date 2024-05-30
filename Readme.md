# Mill-native-image Scala Mill Plugin

This is a [Mill](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html) plugin which allows building executables using [GraalVM native-image](https://www.graalvm.org/latest/reference-manual/native-image/) for your modules.

The plugin allow build both natively in the current host (for example MacOS) or building in a Docker container to create Linux executables on any platform.

## Installing

To start using this plugin you'll have to include the following import in your `build.sc` file:

```scala
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.26`
import io.github.alexarchambault.millnativeimage.NativeImage
```

## Usage

Sample configuration:

```scala
import mill._, mill.scalalib._
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.23`
import io.github.alexarchambault.millnativeimage.NativeImage

object hello extends ScalaModule with NativeImage {
  def scalaVersion = "3.3.0-RC2"
  def ivyDeps = ... // Your deps here

  def nativeImageName         = "hello"
  def nativeImageMainClass    = "com.domain.Main.MainApp"
  def nativeImageClassPath    = runClasspath()
  def nativeImageGraalVmJvmId = "graalvm-java17:22.3.1"
  def nativeImageOptions = Seq(
    "--no-fallback",
    "--enable-url-protocols=http,https",
    "-Djdk.http.auth.tunneling.disabledSchemes=",
  ) ++ (if (sys.props.get("os.name").contains("Linux")) Seq("--static") else Seq.empty)

  object test extends Tests {
    // ...
  }
}
```

This will build an executable suited for the current host platform (Eg. an Intel MacOS):

```sh
mill hello.nativeImage
```

Will output the folowing with the binary path:

```sh
...
------------------------------------------------------------------------------------------------------------------------
                        5.9s (4.9% of total time) in 32 GCs | Peak RSS: 5.71GB | CPU load: 5.84
------------------------------------------------------------------------------------------------------------------------
Produced artifacts:
 /Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello (executable
)
 /Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello.build_artifacts.txt (txt)
========================================================================================================================
Finished generating 'hello' in 2m 0s.

❯ file /Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello
/Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello: Mach-O 64-bit executable x86_64

❯ /Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello
Started server on http://localhost:8080
```

To build an executable for Linux inside a Docker container (for distribution for example), you need to add a `nativeImageDockerParams` task defining the container image building parameters and base configuration.

In the example below, there is a task that checks if the `DOCKER_NATIVEIMAGE` is set and build in Docker instead. This requires [Docker](https://www.docker.com/) to be installed and running in the current host.

```scala
object hello extends ScalaModule with NativeImage {
  ...
  def isDockerBuild = T.input(T.ctx.env.get("DOCKER_NATIVEIMAGE") != None)
  def nativeImageDockerParams = T {
    if (isDockerBuild()) {
      Some(
        NativeImage.DockerParams(
          imageName = "ubuntu:28.04",
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
```

Running it:

```sh
❯ DOCKER_NATIVEIMAGE=1 ./mill show hello.nativeImage

# The "show" command shows the full path of the generated executable

...
------------------------------------------------------------------------------------------------------------------------
Produced artifacts:

/data/output (executable)

/data/output.build_artifacts.txt (txt)
========================================================================================================================
Finished generating 'output' in 6m 8s.
[1/1] show > [45/49] hello.nativeImageDockerParams
"ref:ad8c2fbf:/Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello"

❯ file /Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello
/Users/myuser/repos/scala/mill-native-image/example/out/hello/nativeImage.dest/hello: ELF 64-bit LSB pie executable, x86-64, version 1 (SYSV), dynamically linked, interpreter /lib64/ld-linux-x86-64.so.2, BuildID[sha1]=27b4934e27e460ffb0a797ee0f7ae61d2a25bd3a, for GNU/Linux 3.2.0, with debug_info, not stripped
```

A complete sample project is provided in [./example](./example).
