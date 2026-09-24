package yaku.ui.reader.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import yaku.core.common.util.system.logcat
import yaku.translation.store.ModelPack

/**
 * Downloads a translation pack independently of the screen that asked for it.
 *
 * A pack is a few hundred megabytes, which is minutes of downloading. Run from the settings screen,
 * the download was tied to that screen: leaving it cancelled the collector but not the blocking
 * read underneath, so the download went on out of sight, and a second tap on Download started
 * another writer on the same partial file. Owned here, it runs to the end with Settings closed,
 * Settings shows its progress again on return, and only one runs at a time.
 */
@Inject
@SingleIn(AppScope::class)
class PackInstaller(
    private val translator: PageTranslator,
    private val preferences: TranslationPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts downloading [pack]. Ignored while another download is running. */
    fun start(pack: ModelPack) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Running(pack, 0f)
            try {
                translator.repository.download(pack).collect { _state.value = State.Running(pack, it.fraction) }
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Pack download failed: ${pack.id}" }
                _state.value = State.Failed(pack, e.message)
                return@launch
            }

            // Confirm against disk rather than trusting the flow to have finished its work. That
            // is what "Downloaded" should mean, and it is cheap to verify.
            if (translator.repository.installedPack(pack.id) == null) {
                _state.value = State.Incomplete(pack)
                return@launch
            }
            adoptIfNoneSelected(pack)
            _state.value = State.Finished(pack)
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Selects the new pack when nothing valid is selected; a deliberate choice is left alone. */
    private suspend fun adoptIfNoneSelected(pack: ModelPack) {
        val current = preferences.activePackId.get()
        if (translator.repository.installedPacks().none { it.id == current }) {
            preferences.activePackId.set(pack.id)
            translator.release()
        }
    }

    sealed interface State {
        data object Idle : State
        data class Running(val pack: ModelPack, val fraction: Float) : State
        data class Finished(val pack: ModelPack) : State
        data class Failed(val pack: ModelPack, val message: String?) : State

        /** The download reported success, but the pack is not on disk. */
        data class Incomplete(val pack: ModelPack) : State
    }
}
