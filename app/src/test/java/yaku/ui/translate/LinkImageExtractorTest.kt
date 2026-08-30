package yaku.ui.translate

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Covers the part of the scraper that decides what on a page is a manga page.
 *
 * The extraction is exercised directly rather than through [LinkImageExtractor.imagesFrom], which
 * would need a socket. Readers differ mostly in *where* they put the page URL, so the fixtures
 * here are the markup shapes that difference produces.
 */
class LinkImageExtractorTest {

    private val extractor = LinkImageExtractor(OkHttpClient())

    private fun extract(body: String): List<String> =
        extractor.extractFromHtml(Jsoup.parse(body, BASE)).map { it.toString() }

    @Test
    fun `takes the first candidate out of a relative srcset`() {
        // The descriptor makes the attribute end in "2x", so testing the whole value for an
        // image extension rejects a perfectly ordinary responsive image.
        extract("""<img srcset="pages/01.jpg 1x, pages/01@2x.jpg 2x">""")
            .shouldContainExactly("$BASE/pages/01.jpg")
    }

    @Test
    fun `takes the first candidate out of an absolute srcset`() {
        extract("""<img srcset="https://cdn.example.com/p/01.jpg 800w, https://cdn.example.com/p/01-lg.jpg 1600w">""")
            .shouldContainExactly("https://cdn.example.com/p/01.jpg")
    }

    @Test
    fun `prefers the lazy-loaded page over the placeholder in src`() {
        extract("""<img src="blank.gif" data-src="pages/01.jpg">""")
            .shouldContainExactly("$BASE/pages/01.jpg")
    }

    @Test
    fun `reads lazy attributes the ranked list does not name`() {
        // An extensionless CDN path is common, and only worth trusting on an attribute that is
        // known to carry images.
        extract("""<img data-lazy="https://cdn.example.com/p/AbC123">""")
            .shouldContainExactly("https://cdn.example.com/p/AbC123")
    }

    @Test
    fun `ignores a link to another page hiding on the image element`() {
        // Any value starting with http used to qualify, so the next chapter was scraped as
        // though it were a page and then fetched as an image.
        extract("""<img data-href="https://example.com/chapter/2" alt="next">""")
            .shouldBeEmpty()
    }

    @Test
    fun `reads a noscript fallback`() {
        extract("""<noscript><img src="pages/01.jpg"></noscript>""")
            .shouldContainExactly("$BASE/pages/01.jpg")
    }

    @Test
    fun `reads a page list embedded in a script`() {
        extract("""<script>var pages = ["\/img\/01.jpg","\/img\/02.jpg"];</script>""")
            .shouldContainExactly("$BASE/img/01.jpg", "$BASE/img/02.jpg")
    }

    @Test
    fun `drops site furniture by name`() {
        extract(
            """
            <img src="pages/01.jpg">
            <img src="assets/logo.png">
            <img src="assets/spinner.gif">
            """.trimIndent(),
        ).shouldContainExactly("$BASE/pages/01.jpg")
    }

    @Test
    fun `drops images the page declares as too small to be a page`() {
        extract("""<img src="pages/01.jpg" width="32" height="32">""").shouldBeEmpty()
    }

    @Test
    fun `keeps the pages in the order they appear`() {
        extract(
            """
            <img src="pages/01.jpg"><img src="pages/02.jpg"><img src="pages/03.jpg">
            """.trimIndent(),
        ).shouldContainExactly("$BASE/pages/01.jpg", "$BASE/pages/02.jpg", "$BASE/pages/03.jpg")
    }

    @Test
    fun `returns each page once however many places it appears in`() {
        extract(
            """
            <img src="pages/01.jpg">
            <a href="pages/01.jpg">full size</a>
            <script>var p = ["\/pages\/01.jpg"];</script>
            """.trimIndent(),
        ).size shouldBe 1
    }

    private companion object {
        const val BASE = "https://reader.example.com"
    }
}
