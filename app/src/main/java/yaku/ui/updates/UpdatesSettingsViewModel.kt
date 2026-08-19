package yaku.ui.updates

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import yaku.core.common.preference.Preference
import yaku.core.common.preference.TriState
import yaku.core.common.preference.getAndSet
import yaku.domain.category.interactor.GetCategories
import yaku.domain.category.model.Category
import yaku.domain.updates.service.UpdatesPreferences

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class UpdatesSettingsViewModel(
    val updatesPreferences: UpdatesPreferences,
    val getCategories: GetCategories,
) : ViewModel() {

    val includedCategories = updatesPreferences.filterIncludedCategories
    val excludedCategories = updatesPreferences.filterExcludedCategories

    fun cycleCategory(category: Category) {
        when (category.id) {
            in includedCategories.get() -> {
                includedCategories.getAndSet { it - category.id }
                excludedCategories.getAndSet { it + category.id }
            }

            in excludedCategories.get() -> excludedCategories.getAndSet { it - category.id }
            else -> includedCategories.getAndSet { it + category.id }
        }
    }

    fun toggleFilter(preference: (UpdatesPreferences) -> Preference<TriState>) {
        preference(updatesPreferences).getAndSet {
            it.next()
        }
    }
}
