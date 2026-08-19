package yaku.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import yaku.core.common.util.lang.withNonCancellableContext
import yaku.domain.library.service.LibraryPreferences
import yaku.domain.manga.interactor.GetFavorites
import yaku.domain.manga.interactor.SetMangaChapterFlags
import yaku.domain.manga.model.Manga

@Inject
class SetMangaDefaultChapterFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val getFavorites: GetFavorites,
) {

    suspend fun await(manga: Manga) {
        withNonCancellableContext {
            with(libraryPreferences) {
                setMangaChapterFlags.awaitSetAllFlags(
                    mangaId = manga.id,
                    unreadFilter = filterChapterByRead.get(),
                    downloadedFilter = filterChapterByDownloaded.get(),
                    bookmarkedFilter = filterChapterByBookmarked.get(),
                    sortingMode = sortChapterBySourceOrNumber.get(),
                    sortingDirection = sortChapterByAscendingOrDescending.get(),
                    displayMode = displayChapterByNameOrNumber.get(),
                )
            }
        }
    }

    suspend fun awaitAll() {
        withNonCancellableContext {
            getFavorites.await().forEach { await(it) }
        }
    }
}
