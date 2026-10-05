package com.domain.Main

import zio.*
import zio.http.*

object MainApp extends ZIOAppDefault:
  val port        = 8080
  val configLayer = ZLayer.succeed(Server.Config.default.port(port))

  override val run =
    Console.printLine(s"Started server on http://localhost:$port") *>
      Server.serve(RootRoute()).provide(configLayer, Server.live)

object RootRoute:
  def apply(): Routes[Any, Response] =
    Routes(
      Method.GET / Root -> handler(Response.text("Hello World!"))
    )
