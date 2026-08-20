package yaku.ui.translate

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import yaku.core.common.util.system.logcat

/**
 * Turns a pasted link into the list of page images to translate.
 *
 * A link is either an image or a document that contains images, and which one it is cannot be
 * told from the URL: plenty of image hosts serve `/p/AbC123` with no extension, and plenty of
 * chapter readers use `.php`. So the content type of the response decides, not the path.
 */
class LinkImageExtractor(private val client: OkHttpClient) {

    suspend fun imagesFrom(rawUrl: String): Result {
        val url = rawUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }.toHttpUrlOrNull() ?: return Result.BadUrl

        val response = try {
            client.newCall(GET(url.toString())).await()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not fetch $url" }
            return Result.Unreachable(e.message ?: "")
        }

        return response.use {
            if (!it.isSuccessful) return Result.HttpError(it.code)

            val contentType = it.header("Content-Type").orEmpty().substringBefore(';').trim()
            when {
                // The body is already downloaded by the time the type is known, so hand it back
                // rather than making the caller fetch the same bytes a second time.
                contentType.startsWith("image/") -> Result.SingleImage(url, it.body.bytes())
                contentType.startsWith("text/html") || contentType.contains("xhtml") -> {
                    val images = extractFromHtml(it.body.string(), url)
                    if (images.isEmpty()) Result.NoImages else Result.Images(images)
                }
                else -> Result.UnsupportedType(contentType.ifBlank { "unknown" })
            }
        }
    }

    /**
     * Pull page images out of a chapter or gallery document.
     *
     * Readers rarely put the page in a plain `src`: it is usually behind a lazy-loading attribute
     * with a placeholder in `src`, so those are checked first. Everything is resolved against the
     * document's own base URL, since relative paths are the norm.
     */
    private fun extractFromHtml(html: String, base: HttpUrl): List<HttpUrl> {
        val document = Jsoup.parse(html, base.toString())

        return document.select("img, source")
            .asSequence()
            .mapNotNull { element ->
                LAZY_ATTRIBUTES
                    .firstNotNullOfOrNull { attribute ->
                        element.attr("abs:$attribute").takeIf { it.isNotBlank() }
                    }
                    ?.let { candidate ->
                        // srcset holds "url 1x, url 2x"; the first entry is enough.
                        candidate.substringBefore(',').trim().substringBefore(' ')
                    }
                    ?.toHttpUrlOrNull()
                    ?.takeUnless { looksLikeChrome(element.attr("width"), element.attr("height")) }
            }
            .distinct()
            .take(MAX_IMAGES)
            .toList()
    }

    /**
     * Drop images the page itself declares as tiny.
     *
     * Site chrome - logos, share buttons, spacer gifs - carries explicit small dimensions far more
     * often than page scans do, and translating a 16px icon costs a full model pass to produce
     * nothing. Images with no declared size are kept: absence of a hint is not evidence.
     */
    private fun looksLikeChrome(width: String, height: String): Boolean {
        val w = width.toIntOrNull()
        val h = height.toIntOrNull()
        return (w != null && w < MIN_DIMENSION) || (h != null && h < MIN_DIMENSION)
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
        data object NoImages : Result
        data class UnsupportedType(val contentType: String) : Result
        data class HttpError(val code: Int) : Result
        data class Unreachable(val reason: String) : Result
    }

    private companion object {
        val LAZY_ATTRIBUTES = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-srcset",
            "srcset",
            "src",
        )
        const val MAX_IMAGES = 60
        const val MIN_DIMENSION = 120
    }
}
