package yaku.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import yaku.core.common.FeatureFlags
import yaku.core.migration.Migration
import yaku.core.migration.MigrationContext
import yaku.domain.base.BasePreferences
import kotlin.uuid.ExperimentalUuidApi

@Inject
@ContributesIntoSet(AppScope::class)
class InstallationIdMigration(
    private val basePreferences: BasePreferences,
) : Migration {
    override val version: Float = Migration.ALWAYS

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val installationId = basePreferences.installationId
        if (!installationId.isSet()) installationId.set(FeatureFlags.newInstallationId())
        return true
    }
}
