package yaku.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.Source
import yaku.data.backup.models.BackupManga
import yaku.data.backup.models.BackupSource
import yaku.domain.source.service.SourceManager

@Inject
class SourcesBackupCreator(
    private val sourceManager: SourceManager,
) {

    operator fun invoke(mangas: List<BackupManga>): List<BackupSource> {
        return mangas
            .asSequence()
            .map(BackupManga::source)
            .distinct()
            .map(sourceManager::getOrStub)
            .map { it.toBackupSource() }
            .toList()
    }
}

private fun Source.toBackupSource() =
    BackupSource(
        name = this.name,
        sourceId = this.id,
    )
