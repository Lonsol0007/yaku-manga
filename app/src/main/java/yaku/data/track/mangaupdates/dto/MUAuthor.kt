package yaku.data.track.mangaupdates.dto

import kotlinx.serialization.Serializable

@Serializable
data class MUAuthor(
    val name: String,
    val type: String,
)
