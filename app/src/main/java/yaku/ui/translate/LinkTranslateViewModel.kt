package yaku.ui.translate

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.buffer
import okio.sink
import yaku.core.common.util.system.logcat
import yaku.data.saver.Image
import yaku.data.saver.ImageSaver
import yaku.data.saver.Location
import yaku.ui.reader.translation.PageTranslator
import java.io.File

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LinkTranslateViewModel(
    private val context: Context,
    networkHelper: NetworkHelper,
    private val pageTranslator: PageTranslator,
    private val imageSaver: ImageSaver,
) : ViewModel() {

    private val client = networkHelper.client
    private val extractor = LinkImageExtractor(client)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Cleared on every new run so a long session cannot fill the cache directory. */
    private val outputDir = File(context.cacheDir, "link-translate")

    fun updateUrl(url: String) = _state.update { it.copy(url = url, error = null) }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(stage = Stage.Idle) }
    }

    fun translate() {
        val url = _state.value.url.trim()
        if (url.isEmpty() || job?.isActive == true) return

        if (!pageTranslator.isEnabled()) {
            _state.update { it.copy(error = Error.TranslationOff) }
            return
        }

        job = viewModelScope.launch {
            // An exception escaping a viewModelScope coroutine reaches the default handler and
            // takes the process down. Nothing in a translation run is worth crashing over - the
            // worst honest outcome is telling the user it did not work.
            runCatching { runTranslation(url) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    logcat(LogPriority.ERROR, failure) { "Link translation failed" }
                    fail(Error.Unreachable(failure.message ?: failure::class.simpleName.orEmpty()))
                }
        }
    }

    private suspend fun runTranslation(url: String) {
        // Off the main thread: deleting a directory of translated pages is disk work, and this
        // coroutine runs on Dispatchers.Main.immediate.
        withContext(Dispatchers.IO) { resetOutputDir() }

        // A link that was itself an image arrives with its bytes already downloaded; reusing
        // them avoids pulling the same file twice.
        var prefetched: ByteArray? = null
        var pageUrl: HttpUrl? = null
        val urls = when (val result = extractor.imagesFrom(url)) {
            is LinkImageExtractor.Result.SingleImage -> {
                prefetched = result.bytes
                listOf(result.url)
            }
            is LinkImageExtractor.Result.Images -> {
                pageUrl = url.toHttpUrlOrNull()
                result.urls
            }
            LinkImageExtractor.Result.BadUrl -> return fail(Error.BadUrl)
            is LinkImageExtractor.Result.NoImages -> return fail(Error.NoImages(result.detail))
            is LinkImageExtractor.Result.UnsupportedType ->
                return fail(Error.UnsupportedType(result.contentType))
            is LinkImageExtractor.Result.HttpError -> return fail(Error.Http(result.code))
            is LinkImageExtractor.Result.Unreachable -> return fail(Error.Unreachable(result.reason))
        }

        _state.update { it.copy(stage = Stage.Translating(done = 0, total = urls.size)) }

        urls.forEachIndexed { index, imageUrl ->
            val page = runCatching { translateOne(imageUrl, index, prefetched, pageUrl) }
                .onFailure { logcat(LogPriority.WARN, it) { "Failed on $imageUrl" } }
                .getOrNull()

            _state.update { current ->
                current.copy(
                    // Pages appear as they finish; on a long chapter the first page is
                    // readable while the rest are still going.
                    pages = if (page != null) current.pages + page else current.pages,
                    stage = Stage.Translating(done = index + 1, total = urls.size),
                )
            }
        }

        _state.update {
            it.copy(
                stage = Stage.Idle,
                error = if (it.pages.isEmpty()) Error.AllFailed else null,
            )
        }
    }

    private suspend fun translateOne(
        url: HttpUrl,
        index: Int,
        prefetched: ByteArray?,
        pageUrl: HttpUrl?,
    ): Page = withContext(Dispatchers.IO) {
        val bytes = if (prefetched != null) {
            Buffer().write(prefetched).use { pageTranslator.translate(it).readByteArray() }
        } else {
            // Present the page the image belongs to; hosts reject bare hotlinks with 403.
            val request = pageUrl
                ?.let { GET(url.toString(), LinkImageExtractor.refererHeaders(it)) }
                ?: GET(url.toString())
            client.newCall(request).await().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body.source().use { pageTranslator.translate(it).readByteArray() }
            }
        }

        val file = File(outputDir, "page_%03d.png".format(index))
        file.sink().buffer().use { it.write(bytes) }
        Page(source = url.toString(), file = file)
    }

    /**
     * Drop the translated pages.
     *
     * They live in the cache directory, so leaving them costs disk until the next run replaces
     * them - and a screen still full of the last chapter is a poor starting point for the next.
     */
    fun clear() {
        job?.cancel()
        job = null
        _state.update { State(url = it.url) }
        viewModelScope.launch { withContext(Dispatchers.IO) { resetOutputDir() } }
    }

    /**
     * Copy the translated pages into the gallery.
     *
     * Everything this screen produces is otherwise cache, which Android may delete at any time
     * and which `clear` deletes deliberately. Saving is the only way to keep a result.
     */
    fun save() {
        val pages = _state.value.pages
        if (pages.isEmpty()) return

        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                pages.count { page ->
                    runCatching {
                        imageSaver.save(
                            Image.Page(
                                inputStream = { page.file.inputStream() },
                                name = page.file.nameWithoutExtension,
                                location = Location.Pictures.create(),
                            ),
                        )
                    }
                        .onFailure { logcat(LogPriority.WARN, it) { "Could not save ${page.file}" } }
                        .isSuccess
                }
            }
            _state.update { it.copy(savedCount = saved) }
        }
    }

    private fun fail(error: Error) {
        _state.update { it.copy(stage = Stage.Idle, error = error) }
    }

    private fun resetOutputDir() {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
    }

    override fun onCleared() {
        outputDir.deleteRecursively()
    }

    @Immutable
    data class State(
        val url: String = "",
        val stage: Stage = Stage.Idle,
        val pages: List<Page> = emptyList(),
        val error: Error? = null,
        /** Pages written to the gallery by the last save, or null if none has run. */
        val savedCount: Int? = null,
    ) {
        val isWorking: Boolean get() = stage != Stage.Idle
    }

    @Immutable
    sealed interface Stage {
        data object Idle : Stage
        data object Fetching : Stage
        data class Translating(val done: Int, val total: Int) : Stage
    }

    @Immutable
    data class Page(val source: String, val file: File)

    @Immutable
    sealed interface Error {
        data object BadUrl : Error
        data class NoImages(val detail: String) : Error
        data object AllFailed : Error
        data object TranslationOff : Error
        data class UnsupportedType(val contentType: String) : Error
        data class Http(val code: Int) : Error
        data class Unreachable(val reason: String) : Error
    }
}
