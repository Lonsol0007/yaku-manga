package yaku.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import yaku.core.migration.Migration
import yaku.core.migration.MigrationContext
import yaku.ui.reader.translation.TranslationPreferences

/**
 * Adds the manifest of packs that need this version beside the original one.
 *
 * A source list is stored once it has been edited, and a stored list never picks up a new
 * default. Anyone who had ever added or removed a source would go on reading only the original
 * manifest and never be offered the packs this version can run. A list that still holds the
 * original official source gets the new one next to it; a list without it was trimmed on purpose
 * and is left alone.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class TranslationPackSourcesMigration(
    private val translationPreferences: TranslationPreferences,
) : Migration {
    override val version: Float = 36f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val sources = translationPreferences.packSources
        // An unset list already reads as the new defaults. Writing it here would store them, and a
        // stored list is exactly what stops the next change of defaults from arriving.
        if (!sources.isSet()) return true

        val current = sources.get()
        if (TranslationPreferences.PACK_SOURCE_V1 in current) {
            sources.set(current + TranslationPreferences.PACK_SOURCE_V2)
        }
        return true
    }
}
