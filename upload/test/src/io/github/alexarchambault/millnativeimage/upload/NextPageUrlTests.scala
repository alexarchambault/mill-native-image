package io.github.alexarchambault.millnativeimage.upload

import zio.test.*

object NextPageUrlTests extends ZIOSpecDefault {

  def spec = suite("Upload.nextPageUrl")(
    test("finds the next page among other links") {
      val header =
        """<https://api.github.com/repositories/1/releases?per_page=100&page=1>; rel="prev", """ +
          """<https://api.github.com/repositories/1/releases?per_page=100&page=3>; rel="next", """ +
          """<https://api.github.com/repositories/1/releases?per_page=100&page=5>; rel="last", """ +
          """<https://api.github.com/repositories/1/releases?per_page=100&page=1>; rel="first""""
      assertTrue(
        Upload.nextPageUrl(header).contains("https://api.github.com/repositories/1/releases?per_page=100&page=3")
      )
    },
    test("finds the next page when it comes first") {
      val header = """<https://example.com/a?page=2>; rel="next", <https://example.com/a?page=4>; rel="last""""
      assertTrue(Upload.nextPageUrl(header).contains("https://example.com/a?page=2"))
    },
    test("returns None on the last page") {
      val header = """<https://example.com/a?page=3>; rel="prev", <https://example.com/a?page=1>; rel="first""""
      assertTrue(Upload.nextPageUrl(header).isEmpty)
    },
    test("handles unquoted, multi-valued and differently cased rel parameters") {
      assertTrue(
        Upload.nextPageUrl("<https://example.com/a?page=2>; rel=next").contains("https://example.com/a?page=2"),
        Upload.nextPageUrl(
          """<https://example.com/a?page=2>; rel="next last""""
        ).contains("https://example.com/a?page=2"),
        Upload.nextPageUrl("""<https://example.com/a?page=2>; REL="next"""").contains("https://example.com/a?page=2"),
      )
    },
    test("doesn't match rel values that merely contain next") {
      assertTrue(Upload.nextPageUrl("""<https://example.com/a?page=2>; rel="nextish"""").isEmpty)
    },
    test("returns None on an empty header") {
      assertTrue(Upload.nextPageUrl("").isEmpty)
    },
  )
}
