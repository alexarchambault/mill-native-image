package com.domain.Main

import zio.http.*
import zio.http.model.*
import zio.test.*

object HelloWorldSpec extends ZIOSpecDefault {

  val rootroute = RootRoute()
  def spec =
    suite("Main application")(
      test("root route should return text string") {
        for {
          response <- rootroute(Request.get(URL(!!)))
          body     <- response.body.asString
        } yield assertTrue(
          response.status == Status.Ok,
          body == "Hello World!",
        )
      }
    )
}
