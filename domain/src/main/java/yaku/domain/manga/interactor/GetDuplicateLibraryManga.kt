package yaku.domain.manga.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.manga.model.Manga
import yaku.domain.manga.model.MangaWithChapterCount
import yaku.domain.manga.repository.MangaRepository

@Inject
class GetDuplicateLibraryManga(
    private val mangaRepository: MangaRepository,
) {

    suspend operator fun invoke(manga: Manga): List<MangaWithChapterCount> {
        return mangaRepository.getDuplicateLibraryManga(manga.id, manga.title.lowercase())
    }
}
