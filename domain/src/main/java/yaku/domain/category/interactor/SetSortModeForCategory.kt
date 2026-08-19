package yaku.domain.category.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.category.model.Category
import yaku.domain.category.repository.CategoryRepository
import yaku.domain.library.model.LibrarySort
import yaku.domain.library.model.plus
import yaku.domain.library.service.LibraryPreferences
import kotlin.random.Random

@Inject
class SetSortModeForCategory(
    private val preferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(categoryId: Long?, type: LibrarySort.Type, direction: LibrarySort.Direction) {
        val category = categoryId?.let { categoryRepository.get(it) }
        val flags = (category?.flags ?: 0) + type + direction
        if (type == LibrarySort.Type.Random) {
            preferences.randomSortSeed.set(Random.nextInt())
        }
        if (category != null && preferences.categorizedDisplaySettings.get()) {
            categoryRepository.updateFlags(categoryId = category.id, flags = flags)
        } else {
            preferences.sortingMode.set(LibrarySort(type, direction))
            categoryRepository.updateAllFlags(flags)
        }
    }

    suspend fun await(
        category: Category?,
        type: LibrarySort.Type,
        direction: LibrarySort.Direction,
    ) {
        await(category?.id, type, direction)
    }
}
