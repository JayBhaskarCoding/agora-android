package com.example.agora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
data class AuthorProfile(
    @SerialName("first_name") val firstName: String? = "User",
    @SerialName("last_name") val lastName: String? = null,
    val handle: String? = "user",
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class Post(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: String? = "",
    val likes: Int = 0,
    val comments: Int = 0,
    @SerialName("media_urls") val mediaUrls: List<String> = emptyList(),
    @SerialName("image_urls") val fallbackImageUrls: List<String> = emptyList(),
    val isLikedByMe: Boolean = false,
    val myReaction: String? = null,
    @SerialName("profiles") val authorProfile: AuthorProfile? = null
) {
    val imageUrls: List<String> get() = mediaUrls.ifEmpty { fallbackImageUrls }
    val timeAgo: String get() = formatTimestamp(createdAt)

    val firstName: String get() = authorProfile?.firstName?.ifBlank { null } ?: "User"
    val handle: String get() = authorProfile?.handle?.ifBlank { null } ?: "user"
    val userAvatarUrl: String? get() = authorProfile?.avatarUrl

    private fun formatTimestamp(instantString: String?): String {
        if (instantString.isNullOrBlank()) return "Just now"
        return try {
            val past = Instant.parse(instantString)
            val now = Instant.now()

            val days = ChronoUnit.DAYS.between(past, now)
            if (days > 0) return "${days}d"

            val hours = ChronoUnit.HOURS.between(past, now)
            if (hours > 0) return "${hours}h"

            val minutes = ChronoUnit.MINUTES.between(past, now)
            if (minutes > 0) return "${minutes}m"

            "Just now"
        } catch (_: Exception) {
            "Just now"
        }
    }
}
