package yaku.domain.category.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.category.repository.CategoryRepository
import yaku.domain.library.model.plus
import yaku.domain.library.service.LibraryPreferences

@Inject
class ResetCategoryFlags(
    private val preferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await() {
        val sort = preferences.sortingMode.get()
        categoryRepository.updateAllFlags(sort.type + sort.direction)
    }
}
