package yaku.domain.source.interactor

import dev.zacsweers.metro.Inject
import yaku.core.common.preference.getAndSet
import yaku.domain.source.model.Source
import yaku.domain.source.service.SourcePreferences

@Inject
class ToggleSourcePin(
    private val preferences: SourcePreferences,
) {

    fun await(source: Source) {
        val isPinned = source.id.toString() in preferences.pinnedSources.get()
        preferences.pinnedSources.getAndSet { pinned ->
            if (isPinned) pinned.minus("${source.id}") else pinned.plus("${source.id}")
        }
    }
}
