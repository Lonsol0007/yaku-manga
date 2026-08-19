package eu.kanade.tachiyomi.util.chapter

import yaku.data.download.DownloadCache
import yaku.domain.chapter.model.Chapter
import yaku.domain.manga.model.Manga
import yaku.source.local.isLocal

/**
 * Returns a copy of the list with not downloaded chapters removed.
 */
fun List<Chapter>.filterDownloaded(manga: Manga, downloadCache: DownloadCache): List<Chapter> {
    if (manga.isLocal()) return this

    return filter { downloadCache.isChapterDownloaded(it.name, it.scanlator, it.url, manga.title, manga.source, false) }
}
