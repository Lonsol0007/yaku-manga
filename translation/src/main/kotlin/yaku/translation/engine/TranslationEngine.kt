package yaku.translation.engine

import android.graphics.Bitmap
import yaku.translation.model.PageTranslation
import yaku.translation.model.TranslationLanguage
import yaku.translation.model.TranslationProgress
import java.io.Closeable

/**
 * A complete on-device translation backend.
 *
 * Implementations must never send page content off the device. The interface exists so the ONNX
 * backend can be swapped for another local one later without touching the reader; it is
 * deliberately not general enough to accommodate a network service.
 */
interface TranslationEngine : Closeable {

    val id: String

    /** True once every model file the engine needs is present and loadable. */
    fun isReady(): Boolean

    /** Loads models into memory. Slow (hundreds of ms to seconds); call off the main thread. */
    suspend fun warmUp()

    /**
     * Runs detection, recognition and translation over one page.
     *
     * [onProgress] is called from the calling coroutine's thread as stages complete, so the
     * reader can render partial state instead of a spinner.
     */
    suspend fun translatePage(
        page: Bitmap,
        source: TranslationLanguage,
        target: TranslationLanguage,
        onProgress: (TranslationProgress) -> Unit = {},
    ): PageTranslation
}
