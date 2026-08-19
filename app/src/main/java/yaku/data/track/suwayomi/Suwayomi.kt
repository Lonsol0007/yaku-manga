package yaku.data.track.suwayomi

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.Source
import yaku.R
import yaku.data.database.models.Track
import yaku.data.track.BaseTracker
import yaku.data.track.EnhancedTracker
import yaku.data.track.model.TrackSearch
import yaku.i18n.MR
import yaku.domain.manga.model.Manga as DomainManga
import yaku.domain.track.model.Track as DomainTrack

class Suwayomi(id: Long) : BaseTracker(id, "Suwayomi"), EnhancedTracker {

    val api by lazy { SuwayomiApi(id, appGraph.sourceManager) }

    override fun getLogo() = R.drawable.brand_suwayomi

    companion object {
        const val UNREAD = 1L
        const val READING = 2L
        const val COMPLETED = 3L

        private const val TRACKER_DELETE_KEY = "Tracker Delete"
        private const val TRACKER_DELETE_DEFAULT = false
    }

    override fun getStatusList(): List<Long> = listOf(UNREAD, READING, COMPLETED)

    override fun getStatus(status: Long): StringResource? = when (status) {
        UNREAD -> MR.strings.unread
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): List<String> = listOf()

    override fun displayScore(track: DomainTrack): String = ""

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toLong() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                } else {
                    track.status = READING
                }
            }
        }

        return api.updateProgress(track, getPrefTrackerDelete())
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> {
        TODO("Not yet implemented")
    }

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.getTrackSearch(track.remote_id)
        track.copyPersonalFrom(remoteTrack)
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun login(username: String, password: String) {
        saveCredentials("user", "pass")
    }

    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources(): List<String> = listOf("yaku.extension.all.tachidesk.Tachidesk")

    override suspend fun match(manga: DomainManga): TrackSearch? =
        try {
            api.getTrackSearch(manga.url.getMangaId())
        } catch (e: Exception) {
            null
        }

    override fun isTrackFrom(track: DomainTrack, manga: DomainManga, source: Source?): Boolean =
        track.remoteUrl == manga.url && source?.let { accept(it) } == true

    override fun migrateTrack(track: DomainTrack, manga: DomainManga, newSource: Source): DomainTrack? =
        if (accept(newSource)) {
            track.copy(remoteUrl = manga.url)
        } else {
            null
        }

    private fun String.getMangaId(): Long =
        this.substringAfterLast('/').toLong()

    private fun getPrefTrackerDelete(): Boolean {
        val preferences = api.sourcePreferences()
        return preferences.getBoolean(TRACKER_DELETE_KEY, TRACKER_DELETE_DEFAULT)
    }
}
