package yaku.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import yaku.core.common.util.system.logcat
import yaku.translation.engine.TranslationEngine
import yaku.translation.engine.onnx.OnnxTranslationEngine
import yaku.translation.model.TranslationProgress
import yaku.translation.render.TranslationRenderer
import yaku.translation.store.ModelRepository
import java.io.ByteArrayOutputStream

/**
 * Reader-facing entry point for on-device translation.
 *
 * The reader hands it an encoded page and gets an encoded page back, so translation slots into
 * the existing image pipeline ahead of dual-page splitting and border cropping and everything
 * downstream - zoom, pan, the cache - keeps working unchanged.
 *
 * Work is serialised through a mutex: the models already saturate the cores they are given, and
 * running two pages concurrently only makes the visible page slower.
 */
@Inject
@SingleIn(AppScope::class)
class PageTranslator(
    private val context: Context,
    networkHelper: NetworkHelper,
    private val preferences: TranslationPreferences,
    private val json: Json,
) {

    val repository = ModelRepository(context, networkHelper.client, json)

    private val renderer = TranslationRenderer()
    private val gate = Mutex()

    private var engine: TranslationEngine? = null
    private var engineFor: String? = null

    /** Keyed by the page's content hash so the same page is never translated twice. */
    private val cache = object : LruCache<Int, ByteArray>(preferences.pageCacheSize.get().coerceIn(1, 16)) {
        override fun sizeOf(key: Int, value: ByteArray) = 1
    }

    fun isEnabled(): Boolean = preferences.enabled.get() && preferences.activePackId.get().isNotBlank()

    /**
     * Translates an encoded page image.
     *
     * Returns [source] untouched when translation is off, the models are missing, or anything
     * fails - a page that renders in the original language always beats an error screen.
     */
    suspend fun translate(source: BufferedSource): BufferedSource {
        if (!isEnabled()) return source

        val bytes = source.peek().readByteArray()
        val key = bytes.contentHashCode()
        cache.get(key)?.let { return Buffer().write(it) }

        return gate.withLock {
            // Another page may have populated the entry while we waited for the lock.
            cache.get(key)?.let { return@withLock Buffer().write(it) }

            try {
                val engine = engineOrNull() ?: return@withLock source
                val page = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withLock source

                val result = try {
                    engine.translatePage(
                        page = page,
                        source = preferences.source(),
                        target = preferences.target(),
                        onProgress = ::logProgress,
                    )
                } finally {
                    page.recycle()
                }

                if (!result.hasContent) return@withLock source

                val rendered = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.let { original ->
                        try {
                            renderer.render(original, result)
                        } finally {
                            if (!original.isRecycled) original.recycle()
                        }
                    }
                    ?: return@withLock source

                val encoded = encode(rendered)
                rendered.recycle()
                cache.put(key, encoded)
                Buffer().write(encoded)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Page translation failed; showing the original" }
                source
            }
        }
    }

    /** Builds (or rebuilds) the engine when the selected pack changes. */
    private fun engineOrNull(): TranslationEngine? {
        val packId = preferences.activePackId.get()
        if (packId.isBlank()) return null

        val current = engine
        if (current != null && engineFor == packId) {
            if (current.isReady()) return current
            // The pack was deleted underneath us. Drop the engine rather than holding a handle
            // to missing files, which would otherwise fail identically on every later page.
            current.close()
            engine = null
            engineFor = null
            return null
        }

        current?.close()
        engine = null
        engineFor = null

        val pack = repository.installedPack(packId) ?: return null
        val created = OnnxTranslationEngine(pack, repository, json = json)
        if (!created.isReady()) {
            created.close()
            return null
        }
        engine = created
        engineFor = packId
        return created
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        // Pages are photographic-ish once text is baked in; JPEG keeps the cache small and
        // decodes faster than PNG on the way back into the viewer.
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    private fun logProgress(progress: TranslationProgress) {
        if (progress is TranslationProgress.Failed) {
            logcat(LogPriority.WARN, progress.error) { "Translation stage failed" }
        }
    }

    /**
     * Drops the loaded models.
     *
     * Takes [gate] because closing an OrtSession that another coroutine is inside of frees native
     * memory still in use - that is a SIGSEGV, not a catchable exception. Switching packs in
     * settings while the reader is translating reaches exactly that.
     */
    suspend fun release() = gate.withLock {
        engine?.close()
        engine = null
        engineFor = null
        cache.evictAll()
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
