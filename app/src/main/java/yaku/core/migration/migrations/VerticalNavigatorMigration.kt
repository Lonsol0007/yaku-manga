package yaku.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import yaku.core.common.preference.PreferenceStore
import yaku.core.common.util.lang.withIOContext
import yaku.core.migration.Migration
import yaku.core.migration.MigrationContext
import yaku.ui.reader.setting.ReaderPreferences
import yaku.ui.reader.setting.ReadingMode

@Inject
@ContributesIntoSet(AppScope::class)
class VerticalNavigatorMigration(
    private val preferenceStore: PreferenceStore,
    private val readerPreferences: ReaderPreferences,
) : Migration {
    override val version: Float = 25f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 24) {
            val oldVerticalNavigator = preferenceStore.getBoolean("pref_webtoon_vertical_navigator", true)
            if (oldVerticalNavigator.get()) {
                readerPreferences.verticalNavigator.set(setOf(ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL))
            }
            if (oldVerticalNavigator.isSet()) oldVerticalNavigator.delete()
        }

        val oldVerticalNavigatorOnLeft = preferenceStore.getBoolean("pref_webtoon_vertical_navigator_on_left", false)
        if (oldVerticalNavigatorOnLeft.isSet()) {
            readerPreferences.verticalNavigatorOnLeft.set(oldVerticalNavigatorOnLeft.get())
            oldVerticalNavigatorOnLeft.delete()
        }

        return@withIOContext true
    }
}
