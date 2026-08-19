package yaku.domain.source.interactor

import dev.zacsweers.metro.Inject
import yaku.core.common.preference.getAndSet
import yaku.domain.source.service.SourcePreferences

@Inject
class ToggleIncognito(
    private val preferences: SourcePreferences,
) {
    fun await(extensions: String, enable: Boolean) {
        preferences.incognitoExtensions.getAndSet {
            if (enable) it.plus(extensions) else it.minus(extensions)
        }
    }
}
