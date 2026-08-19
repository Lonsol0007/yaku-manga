package yaku.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import yaku.data.Database
import yaku.data.backup.models.BackupExtensionStore

@Inject
class ExtensionStoreRestorer(
    private val database: Database,
) {

    suspend operator fun invoke(
        backupStore: BackupExtensionStore,
    ) {
        database.extension_storeQueries.upsert(
            indexUrl = backupStore.indexUrl,
            name = backupStore.name,
            badgeLabel = backupStore.badgeLabel ?: backupStore.name,
            signingKey = backupStore.signingKey,
            contactWebsite = backupStore.contactWebsite,
            contactDiscord = backupStore.contactDiscord,
            isLegacy = backupStore.isLegacy ?: true,
            extensionListUrl = backupStore.extensionListUrl,
        )
    }
}
