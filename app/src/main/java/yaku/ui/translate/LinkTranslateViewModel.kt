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
import okio.buffer
import okio.sink
import yaku.core.common.util.system.logcat
import yaku.ui.reader.translation.PageTranslator
import java.io.File

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LinkTranslateViewModel(
    private val context: Context,
    networkHelper: NetworkHelper,
    private val pageTranslator: PageTranslator,
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
            _state.update { it.copy(stage = Stage.Fetching, pages = emptyList(), error = null) }
            resetOutputDir()

            val urls = when (val result = extractor.imagesFrom(url)) {
                is LinkImageExtractor.Result.Images -> result.urls
                LinkImageExtractor.Result.BadUrl -> return@launch fail(Error.BadUrl)
                LinkImageExtractor.Result.NoImages -> return@launch fail(Error.NoImages)
                is LinkImageExtractor.Result.UnsupportedType ->
                    return@launch fail(Error.UnsupportedType(result.contentType))
                is LinkImageExtractor.Result.HttpError -> return@launch fail(Error.Http(result.code))
                is LinkImageExtractor.Result.Unreachable -> return@launch fail(Error.Unreachable(result.reason))
            }

            _state.update { it.copy(stage = Stage.Translating(done = 0, total = urls.size)) }

            urls.forEachIndexed { index, imageUrl ->
                val page = runCatching { translateOne(imageUrl, index) }
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
    }

    private suspend fun translateOne(url: HttpUrl, index: Int): Page = withContext(Dispatchers.IO) {
        val bytes = client.newCall(GET(url.toString())).await().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.source().use { pageTranslator.translate(it).readByteArray() }
        }

        val file = File(outputDir, "page_%03d.png".format(index))
        file.sink().buffer().use { it.write(bytes) }
        Page(source = url.toString(), file = file)
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
        data object NoImages : Error
        data object AllFailed : Error
        data object TranslationOff : Error
        data class UnsupportedType(val contentType: String) : Error
        data class Http(val code: Int) : Error
        data class Unreachable(val reason: String) : Error
    }
}
