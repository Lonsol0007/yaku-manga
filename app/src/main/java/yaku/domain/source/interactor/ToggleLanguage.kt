package yaku.domain.source.interactor

import dev.zacsweers.metro.Inject
import yaku.core.common.preference.getAndSet
import yaku.domain.source.service.SourcePreferences

@Inject
class ToggleLanguage(
    val preferences: SourcePreferences,
) {

    fun await(language: String) {
        val isEnabled = language in preferences.enabledLanguages.get()
        preferences.enabledLanguages.getAndSet { enabled ->
            if (isEnabled) enabled.minus(language) else enabled.plus(language)
        }
    }
}
