package yaku.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import yaku.core.common.util.system.logcat
import yaku.domain.chapter.model.Chapter
import yaku.domain.chapter.repository.ChapterRepository

@Inject
class GetBookmarkedChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long): List<Chapter> {
        return try {
            chapterRepository.getBookmarkedChaptersByMangaId(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }
}
