package yaku.ui.translate

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import yaku.core.common.util.system.logcat

/**
 * Turns a pasted link into the list of page images to translate.
 *
 * A link is either an image or a document that contains images, and which one it is cannot be
 * told from the URL: plenty of image hosts serve `/p/AbC123` with no extension, and plenty of
 * chapter readers use `.php`. The response decides, not the path.
 */
class LinkImageExtractor(private val client: OkHttpClient) {

    /**
     * Must run off the main thread.
     *
     * `Call.await()` resumes through the caller's dispatcher, and the caller is a viewModelScope
     * coroutine on `Dispatchers.Main.immediate`. Reading the body afterwards - `bytes()` or
     * `string()` - pulls from the socket, and doing that on the main thread is a
     * NetworkOnMainThreadException, which kills the process rather than surfacing as an error.
     */
    suspend fun imagesFrom(rawUrl: String): Result = withContext(Dispatchers.IO) {
        val url = rawUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }.toHttpUrlOrNull() ?: return@withContext Result.BadUrl

        val response = try {
            client.newCall(GET(url.toString())).await()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not fetch $url" }
            return@withContext Result.Unreachable(e.message ?: e::class.simpleName.orEmpty())
        }

        response.use {
            if (!it.isSuccessful) return@withContext Result.HttpError(it.code)

            val contentType = it.header("Content-Type").orEmpty().substringBefore(';').trim()
            val body = it.body.bytes()

            // Content-Type is a claim, not a fact. Image hosts serve pages as
            // application/octet-stream often enough that trusting the header alone rejects real
            // images, so fall back to the file's own magic bytes.
            if (contentType.startsWith("image/") || looksLikeImage(body)) {
                return@withContext Result.SingleImage(url, body)
            }

            val html = String(body)
            if (!contentType.startsWith("text/html") && !contentType.contains("xhtml") &&
                !html.trimStart().startsWith("<", ignoreCase = true)
            ) {
                return@withContext Result.UnsupportedType(contentType.ifBlank { "unknown" })
            }

            val document = Jsoup.parse(html, url.toString())
            val images = extractFromHtml(document)
            if (images.isNotEmpty()) {
                Result.Images(images)
            } else {
                Result.NoImages(describeWhyEmpty(document, html))
            }
        }
    }

    /**
     * Collect page images from a chapter or gallery document.
     *
     * Readers hide the real page URL in a surprising number of places, so this looks in all of
     * them rather than a fixed attribute list:
     *
     * - any attribute on `img`/`source` whose value looks like an image, since lazy-loading
     *   libraries invent their own names (`data-src`, `data-lazy`, `data-original`, `data-echo`…)
     * - `<noscript>` fallbacks, which Jsoup keeps as *text*, so they need re-parsing
     * - `<a href>` pointing at an image, the usual shape of a gallery thumbnail grid
     * - CSS `background-image: url(...)`
     * - inline scripts, where readers commonly embed the whole page list as a JSON array
     */
    internal fun extractFromHtml(document: Document): List<HttpUrl> {
        val base = document.baseUri()
        val found = LinkedHashSet<String>()

        document.select("img, source").forEach { element ->
            if (!isChrome(element)) {
                // One URL per element, best first. Taking every matching attribute collects the
                // low-resolution placeholder sitting in src *as well as* the full page in
                // data-src, and the placeholder usually wins - which is how a chapter comes back
                // as a wall of thumbnails.
                bestAttribute(element)?.let { found += it }
            }
        }

        // Jsoup treats noscript content as text, so its markup has to be parsed a second time.
        document.select("noscript").forEach { noscript ->
            Jsoup.parse(noscript.text(), base).select("img").forEach { img ->
                img.attributes().forEach { attribute ->
                    if (looksLikeImageUrl(attribute.value)) found += attribute.value
                }
            }
        }

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (hasImageExtension(href)) found += href
        }

        document.select("[style*=background-image]").forEach { element ->
            BACKGROUND_URL.findAll(element.attr("style")).forEach { found += it.groupValues[1] }
        }

        // Readers frequently ship the page list as JSON inside a <script>, with the URLs
        // backslash-escaped. Pulling them straight out of the source is what makes a
        // JavaScript-driven reader work at all without running its JavaScript.
        document.select("script").forEach { script ->
            SCRIPT_URL.findAll(script.data()).forEach { match ->
                found += match.value.trim('"', '\'').replace("\\/", "/")
            }
        }

        return found
            .asSequence()
            .map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("data:") }
            .mapNotNull { candidate ->
                // Resolve relatives against the document; absolute values pass through unchanged.
                runCatching { base.toHttpUrlOrNull()?.resolve(candidate) }.getOrNull()
            }
            .filterNot { looksLikeDecoration(it) }
            .distinct()
            .take(MAX_IMAGES)
            .toList()
    }

    /**
     * The single best image URL an element offers.
     *
     * Lazy-loading markup carries two URLs: a placeholder in src, and the real page in one of
     * the data attributes. Ranked names are tried first, and only if none of them match does it
     * fall back to scanning every attribute - which keeps the flexibility that catches unusual
     * loaders without letting a 20px placeholder outrank the page it stands in for.
     */
    private fun bestAttribute(element: Element): String? {
        val ranked = PREFERRED_ATTRIBUTES.firstNotNullOfOrNull { name ->
            firstCandidate(element.attr(name))?.takeIf { looksLikeImageUrl(it) }
        }
        if (ranked != null) return ranked

        // An attribute nobody named is a guess, so it has to look like an image by its own path.
        // The ranked names are known to carry images, which is what lets them accept the
        // extensionless CDN paths many readers use; extending that trust to every attribute
        // scrapes the "next chapter" link off the same element and then fetches it as a page.
        return element.attributes()
            .mapNotNull { firstCandidate(it.value) }
            .firstOrNull { hasImageExtension(it) }
    }

    /**
     * The URL an attribute offers, with any srcset descriptor removed.
     *
     * srcset holds "url 1x, url 2x", so the value has to be cut down *before* it is judged.
     * Testing the whole attribute asks whether a string ending in "2x" looks like an image,
     * which throws away every responsive image whose URLs are relative.
     */
    private fun firstCandidate(value: String): String? =
        value.substringBefore(',').trim().substringBefore(' ').takeIf { it.isNotBlank() }

    /**
     * Explain an empty result in terms of what the page contained.
     *
     * "No images found" is true but useless. A reader that builds itself in JavaScript, a page
     * behind a login wall and a genuine mistake in the URL all need different responses from the
     * person holding the phone.
     */
    private fun describeWhyEmpty(document: Document, html: String): String {
        val imgCount = document.select("img, source").size
        val scriptCount = document.select("script").size
        val title = document.title().take(60)

        return when {
            html.length < SUSPICIOUSLY_SHORT && scriptCount > 0 ->
                "The page is built by JavaScript (${html.length} bytes of HTML, $scriptCount " +
                    "scripts, no images in the source). Try the direct image URL instead."
            imgCount == 0 && scriptCount > 0 ->
                "No <img> tags in the source, only $scriptCount scripts - this reader draws its " +
                    "pages with JavaScript. Try the direct image URL instead."
            imgCount == 0 ->
                "The page has no images at all${if (title.isNotBlank()) " (\"$title\")" else ""}."
            else ->
                "Found $imgCount image tags, but none looked like manga pages - they may be " +
                    "icons, or the pages may load from JavaScript."
        }
    }

    /** True when the page itself declares the element as small enough to be site furniture. */
    private fun isChrome(element: Element): Boolean {
        val w = element.attr("width").toIntOrNull()
        val h = element.attr("height").toIntOrNull()
        return (w != null && w < MIN_DIMENSION) || (h != null && h < MIN_DIMENSION)
    }

    /**
     * Drop by filename what could not be dropped by declared size.
     *
     * Most pages carry a logo, avatars and ad slots with no width/height set. Each one otherwise
     * costs a full detect-recognise-translate pass to produce nothing.
     */
    private fun looksLikeDecoration(url: HttpUrl): Boolean {
        val path = url.encodedPath.lowercase()
        return DECORATION_HINTS.any { path.contains(it) }
    }

    private fun looksLikeImageUrl(value: String): Boolean =
        value.length in 4..2048 &&
            !value.startsWith("data:") &&
            (hasImageExtension(value) || value.startsWith("http") || value.startsWith("//"))

    private fun hasImageExtension(value: String): Boolean {
        val path = value.substringBefore('?').substringBefore('#').lowercase()
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    /** Identify an image by its own header rather than by what the server claimed. */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(index: Int) = bytes[index].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true // JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E -> true // PNG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true // GIF
            at(0) == 0x42 && at(1) == 0x4D -> true // BMP
            // RIFF....WEBP
            at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 && at(9) == 0x45 -> true
            // ....ftyp - AVIF and HEIC
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> true
            else -> false
        }
    }

    sealed interface Result {
        /** A link that was itself an image, with the bytes already in hand. */
        data class SingleImage(val url: HttpUrl, val bytes: ByteArray) : Result {
            override fun equals(other: Any?) =
                this === other || (other is SingleImage && url == other.url && bytes.contentEquals(other.bytes))

            override fun hashCode() = 31 * url.hashCode() + bytes.contentHashCode()
        }

        data class Images(val urls: List<HttpUrl>) : Result
        data object BadUrl : Result
        data class NoImages(val detail: String) : Result
        data class UnsupportedType(val contentType: String) : Result
        data class HttpError(val code: Int) : Result
        data class Unreachable(val reason: String) : Result
    }

    companion object {
        /**
         * Sent when fetching page images.
         *
         * Image hosts routinely reject requests that arrive without the page they belong to,
         * so a scrape that finds the right URLs still gets 403s on every one of them.
         */
        fun refererHeaders(pageUrl: HttpUrl): Headers = Headers.Builder()
            .add("Referer", pageUrl.toString())
            .build()

        /** Highest-fidelity first: data attributes hold the page, src usually holds a stand-in. */
        private val PREFERRED_ATTRIBUTES = listOf(
            "data-src",
            "data-original",
            "data-original-src",
            "data-lazy",
            "data-lazy-src",
            "data-echo",
            "data-image",
            "data-full",
            "data-srcset",
            "srcset",
            "src",
        )

        private val IMAGE_EXTENSIONS =
            listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".jfif")

        private val DECORATION_HINTS = listOf(
            "logo", "icon", "favicon", "avatar", "sprite", "banner",
            "advert", "/ads/", "placeholder", "spinner", "loading",
            // A reader page usually carries the book's cover and a thumbnail of it, and when
            // the pages themselves are drawn by JavaScript those are the only real images in
            // the source. Without this the extractor returns them, and translating a cover
            // looks like success - which is worse than reporting that no pages were found.
            // A chapter's own title page can be called "cover" and would be skipped too; that
            // is a page of artwork with little dialogue, so the trade is worth it.
            "cover", "thumb",
            // Content is never served out of a stylesheet asset directory. This catches
            // promo graphics that carry no size the dimension filter can use - one such
            // banner declares width="100%", which parses as no width at all.
            "/css/",
        )

        private val BACKGROUND_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

        /**
         * Quoted URLs ending in an image extension, including JSON-escaped slashes.
         *
         * The leading slash is optionally escaped too. PHP's json_encode escapes forward
         * slashes unless asked not to, so a page list from a PHP reader arrives with every
         * path escaped - and matching only a bare slash misses the whole chapter.
         */
        private val SCRIPT_URL = Regex(
            """["'](?:https?:)?(?:\\?/\\?/|\\?/)[^"'\s]{4,400}?\.(?:jpg|jpeg|png|webp|gif|avif)(?:\?[^"'\s]{0,200})?["']""",
            RegexOption.IGNORE_CASE,
        )

        private const val MAX_IMAGES = 60
        private const val MIN_DIMENSION = 120
        private const val SUSPICIOUSLY_SHORT = 4096
    }
}
