package yaku.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import yaku.core.common.util.lang.withNonCancellableContext
import yaku.core.common.util.system.logcat
import yaku.domain.category.model.Category
import yaku.domain.category.repository.CategoryRepository

@Inject
class RenameCategory(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(categoryId: Long, name: String) = withNonCancellableContext {
        try {
            categoryRepository.updateName(categoryId = categoryId, name = name)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    suspend fun await(category: Category, name: String) = await(category.id, name)

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
