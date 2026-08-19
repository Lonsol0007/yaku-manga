package yaku.domain.category.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.library.model.LibraryDisplayMode
import yaku.domain.library.service.LibraryPreferences

@Inject
class SetDisplayMode(
    private val preferences: LibraryPreferences,
) {

    fun await(display: LibraryDisplayMode) {
        preferences.displayMode.set(display)
    }
}
