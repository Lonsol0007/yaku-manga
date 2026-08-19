package yaku.data.track.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuCurrentUserResult(
    val data: List<KitsuUser>,
)

@Serializable
data class KitsuUser(
    val id: String,
    val attributes: KitsuUserAttributes,
)

@Serializable
data class KitsuUserAttributes(
    val name: String,
)
