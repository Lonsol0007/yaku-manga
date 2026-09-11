package yaku.core.migration.migrations

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import yaku.core.common.preference.InMemoryPreferenceStore
import yaku.core.migration.MigrationContext
import yaku.ui.reader.translation.TranslationPreferences
import yaku.ui.reader.translation.TranslationPreferences.Companion.PACK_SOURCE_V1
import yaku.ui.reader.translation.TranslationPreferences.Companion.PACK_SOURCE_V2

class TranslationPackSourcesMigrationTest {

    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val migration = TranslationPackSourcesMigration(preferences)

    private fun migrate() = runBlocking {
        migration(MigrationContext(dryrun = false, previousVersion = 35))
    }

    @Test
    fun `adds the new manifest beside the original one`() {
        preferences.packSources.set(setOf(PACK_SOURCE_V1, THIRD_PARTY))

        migrate()

        preferences.packSources.get() shouldBe setOf(PACK_SOURCE_V1, PACK_SOURCE_V2, THIRD_PARTY)
    }

    @Test
    fun `leaves a list the official manifest was removed from`() {
        preferences.packSources.set(setOf(THIRD_PARTY))

        migrate()

        preferences.packSources.get() shouldBe setOf(THIRD_PARTY)
    }

    @Test
    fun `does not store the defaults of a list that was never edited`() {
        // Storing them would stop the next change of defaults from ever arriving.
        migrate()

        preferences.packSources.isSet() shouldBe false
    }

    private companion object {
        const val THIRD_PARTY = "https://packs.example.com/manifest.json"
    }
}
