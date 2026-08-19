package yaku.domain.manga.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.manga.model.Manga
import yaku.domain.manga.repository.MangaRepository

@Inject
class GetMangaByUrlAndSourceId(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(url: String, sourceId: Long): Manga? {
        return mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
    }
}
