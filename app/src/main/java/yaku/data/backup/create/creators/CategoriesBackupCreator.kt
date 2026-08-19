package yaku.data.backup.create.creators

import dev.zacsweers.metro.Inject
import yaku.data.backup.models.BackupCategory
import yaku.data.backup.models.backupCategoryMapper
import yaku.domain.category.interactor.GetCategories
import yaku.domain.category.model.Category

@Inject
class CategoriesBackupCreator(
    private val getCategories: GetCategories,
) {

    suspend operator fun invoke(): List<BackupCategory> {
        return getCategories.await()
            .filterNot(Category::isSystemCategory)
            .map(backupCategoryMapper)
    }
}
