package com.domain.Main

import zio.*
import zio.http.*
import zio.test.*

object HelloWorldSpec extends ZIOSpecDefault:

  val rootroute: Routes[Any, Response] = RootRoute()

  def spec = suite("Main application")(
    test("root route should return text string") {
      for
        response <- rootroute.runZIO(Request.get(URL.root))
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "Hello World!",
      )
    }
  )
