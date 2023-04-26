package com.domain.Main

import zio.*
import zio.http.*
import zio.http.Method

object MainApp extends ZIOAppDefault {
  val port   = 8080
  val configLayer = ZLayer.succeed(Server.Config.default.port(port))

  override val run =
    Console.printLine(s"Started server on http://localhost:$port") *>
      Server.serve(RootRoute()).provide(configLayer, Server.live)
}

object RootRoute {
  def apply(): Http[Any, Nothing, Request, Response] =
    Http.collectZIO[Request] { case Method.GET -> !! =>
      ZIO.succeed(Response.text("Hello World!"))
    }
}
