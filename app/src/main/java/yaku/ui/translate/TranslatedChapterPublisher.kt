package yaku.ui.translate

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import yaku.core.common.util.system.logcat
import yaku.domain.chapter.interactor.GetChaptersByMangaId
import yaku.domain.chapter.interactor.SyncChaptersWithSource
import yaku.domain.manga.interactor.NetworkToLocalManga
import yaku.domain.manga.model.Manga
import yaku.domain.source.service.SourceManager
import yaku.domain.storage.service.StorageManager
import yaku.source.local.LocalSource
import java.io.File

/**
 * Files a set of translated pages as a chapter the reader can open.
 *
 * The reader works from a manga and a chapter in the database, not from a folder of images, and
 * teaching it otherwise would mean guarding every place it assumes a real manga exists - history,
 * tracking, reading progress. Writing the pages where the local source already looks costs none of
 * that: the source turns the folder into a chapter, the usual loader reads it, and the reader is
 * untouched. The translated chapter also survives the cache being cleared, so it can be read again
 * without translating it a second time.
 */
@Inject
class TranslatedChapterPublisher(
    private val storageManager: StorageManager,
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    /** Where the reader should be sent once the pages are filed. */
    data class Target(val mangaId: Long, val chapterId: Long)

    suspend fun publish(sourceUrl: String, pages: List<File>): Target? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val local = sourceManager.get(LocalSource.ID) as? LocalSource ?: run {
            logcat(LogPriority.WARN) { "Local source unavailable; cannot open translation in reader" }
            return@withContext null
        }

        val names = namesFor(sourceUrl)
        if (!writePages(names, pages)) return@withContext null

        val sManga = SManga.create().apply {
            url = names.series
            title = names.series
        }

        // Ask the local source to read back what was just written, rather than describing the
        // chapter ourselves: it decides how a directory becomes a chapter, and a hand-built
        // SChapter that disagreed with it would sync once and then never match again.
        val update = local.getMangaUpdate(sManga, emptyList(), fetchDetails = true, fetchChapters = true)
        val filed = update.chapters.firstOrNull { it.name == names.chapter } ?: run {
            logcat(LogPriority.WARN) { "Local source did not list the chapter just filed: ${names.chapter}" }
            return@withContext null
        }

        val manga = networkToLocalManga(
            Manga.create().copy(url = sManga.url, title = sManga.title, source = LocalSource.ID),
        )
        syncChaptersWithSource.await(update.chapters, manga, local)

        // Sync answers with the chapters it has just added, so a link translated a second time -
        // after installing a better pack, say - got nothing back and the reader never opened.
        // Read the chapter back from the database instead, by the address the source gave it.
        val chapterId = getChaptersByMangaId.await(manga.id).firstOrNull { it.url == filed.url }?.id
            ?: return@withContext null

        Target(mangaId = manga.id, chapterId = chapterId)
    }

    private fun writePages(names: Names, pages: List<File>): Boolean {
        val base = storageManager.getLocalSourceDirectory() ?: run {
            logcat(LogPriority.WARN) { "No local source directory; cannot file the translation" }
            return false
        }

        val seriesDir = base.findFile(names.series)?.takeIf { it.isDirectory }
            ?: base.createDirectory(names.series)
            ?: return false
        val chapterDir = seriesDir.findFile(names.chapter)?.takeIf { it.isDirectory }
            ?: seriesDir.createDirectory(names.chapter)
            ?: return false

        pages.forEachIndexed { index, page ->
            // Padded so the source's natural-order sort keeps page 10 after page 9.
            val target = chapterDir.createFile(PAGE_NAME.format(index)) ?: return false
            runCatching {
                target.openOutputStream().use { out -> page.inputStream().use { it.copyTo(out) } }
            }.onFailure {
                logcat(LogPriority.ERROR, it) { "Could not write ${page.name} into the local source" }
                return false
            }
        }

        // A chapter translated again can come out shorter, and the pages of the earlier run past
        // the new end would otherwise stay on as its last pages. Only files this class names are
        // touched; anything else in the directory is left where it is.
        chapterDir.listFiles().orEmpty()
            .filter { pageIndex(it.name) >= pages.size }
            .forEach { it.delete() }
        return true
    }

    /** The number in a page file's name, or -1 for any file this class did not write. */
    private fun pageIndex(fileName: String?): Int =
        fileName?.let { PAGE_FILE.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private class Names(val series: String, val chapter: String)

    /**
     * Splits a chapter address into a series and a chapter.
     *
     * Readers almost always name the page after both - "some-series-chapter-52.1" - so splitting on
     * the chapter marker groups every chapter of one series under one entry, which is what makes
     * the library entry worth having. When the address does not follow that shape the host stands
     * in for the series, so translations from one site still collect together instead of scattering
     * one library entry per link.
     */
    private fun namesFor(sourceUrl: String): Names {
        val url = sourceUrl.toHttpUrlOrNull()
        val slug = url?.slug().orEmpty()
        val marker = CHAPTER_MARKER.find(slug)

        val series = marker?.let { slug.take(it.range.first) }?.cleaned()?.takeIf { it.isNotBlank() }
            ?: url?.host?.cleaned()
            ?: FALLBACK_SERIES
        val chapter = marker?.let { slug.substring(it.range.first) }?.cleaned()?.takeIf { it.isNotBlank() }
            ?: slug.cleaned().takeIf { it.isNotBlank() }
            ?: FALLBACK_CHAPTER

        return Names(series = series.take(NAME_LIMIT), chapter = chapter.take(NAME_LIMIT))
    }

    private fun HttpUrl.slug(): String =
        pathSegments.lastOrNull { it.isNotBlank() }
            ?.substringBeforeLast('.')
            .orEmpty()

    /** Directory names, so anything a file system objects to has to go. */
    private fun String.cleaned(): String =
        replace('-', ' ')
            .replace('_', ' ')
            .filterNot { it in ILLEGAL_IN_NAMES }
            .trim()
            .replace(WHITESPACE_RUN, " ")

    private companion object {
        const val PAGE_NAME = "page_%03d.jpg"
        const val NAME_LIMIT = 90
        const val FALLBACK_SERIES = "Translations"
        const val FALLBACK_CHAPTER = "Translated pages"

        val PAGE_FILE = Regex("""page_(\d+)\.jpg""")
        val CHAPTER_MARKER = Regex("""chapter[-_ ]?\d""", RegexOption.IGNORE_CASE)
        val ILLEGAL_IN_NAMES = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val WHITESPACE_RUN = Regex("""\s+""")
    }
}
