package yaku.data.track

import yaku.domain.track.model.Track

/**
 * Tracker that support deleting am entry from a user's list.
 */
interface DeletableTracker {

    suspend fun delete(track: Track)
}
