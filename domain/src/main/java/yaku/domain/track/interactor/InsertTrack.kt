package yaku.domain.track.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import yaku.core.common.util.system.logcat
import yaku.domain.track.model.Track
import yaku.domain.track.repository.TrackRepository

@Inject
class InsertTrack(
    private val trackRepository: TrackRepository,
) {

    suspend fun await(track: Track) {
        try {
            trackRepository.insert(track)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitAll(tracks: List<Track>) {
        try {
            trackRepository.insertAll(tracks)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
