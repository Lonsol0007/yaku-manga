package eu.kanade.tachiyomi.util

import yaku.data.cache.CoverCache
import yaku.domain.manga.interactor.UpdateManga
import yaku.domain.manga.model.Manga
import yaku.domain.manga.model.toSManga
import yaku.source.local.image.LocalCoverManager
import yaku.source.local.isLocal
import java.io.InputStream
import kotlin.time.Clock

fun Manga.removeCovers(coverCache: CoverCache): Manga {
    if (isLocal()) return this
    return if (coverCache.deleteFromCache(this, true) > 0) {
        copy(coverLastModified = Clock.System.now().toEpochMilliseconds())
    } else {
        this
    }
}

suspend fun Manga.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateManga: UpdateManga,
    coverCache: CoverCache,
) {
    if (isLocal()) {
        coverManager.update(toSManga(), stream)
        updateManga.awaitUpdateCoverLastModified(id)
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateManga.awaitUpdateCoverLastModified(id)
    }
}
