package yaku.data.track.mangaupdates.dto

import kotlinx.serialization.Serializable
import yaku.data.database.models.Track

@Serializable
data class MURating(
    val rating: Double? = null,
)

fun MURating.copyTo(track: Track): Track {
    return track.apply {
        this.score = rating ?: 0.0
    }
}
