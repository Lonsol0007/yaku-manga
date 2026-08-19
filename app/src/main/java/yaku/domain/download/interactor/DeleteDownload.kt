package yaku.domain.download.interactor

import dev.zacsweers.metro.Inject
import yaku.core.common.util.lang.withNonCancellableContext
import yaku.data.download.DownloadManager
import yaku.domain.chapter.model.Chapter
import yaku.domain.manga.model.Manga
import yaku.domain.source.service.SourceManager

@Inject
class DeleteDownload(
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
) {

    suspend fun awaitAll(manga: Manga, vararg chapters: Chapter) = withNonCancellableContext {
        sourceManager.get(manga.source)?.let { source ->
            downloadManager.deleteChapters(chapters.toList(), manga, source)
        }
    }
}
