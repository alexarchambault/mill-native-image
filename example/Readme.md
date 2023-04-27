# Simple ZIO HTTP Server

This basic HTTP server written in [ZIO](https://zio.dev) and [ZIO-http](https://https://github.com/zio/zio-http) provides a simple "Hello World" in response to a GET in the "/" route.

The idea is to demonstrate how to build a native-image executable built by GraalVM Native Image.

To simply run the application locally, use `./mill hello.run`. To run the tests, use `./mill hello.test`.

To build the executable (requires [GraalVM JDK](https://www.graalvm.org/), use:

```sh
./mill hello.nativeImage
```

test with:

```sh
❯ curl -s http://localhost:8080
Hello World!%
```

To generate a Linux executable using Docker, run:

```sh
❯ DOCKER_NATIVEIMAGE=1 ./mill show hello.nativeImage
```
