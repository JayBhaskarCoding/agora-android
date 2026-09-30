package com.example.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

@Immutable
@Serializable
data class AuthorProfile(
    @SerialName("first_name") val firstName: String? = "User",
    @SerialName("last_name") val lastName: String? = null,
    val handle: String? = "user",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** 'active' or 'closed' — closed accounts were soft-deleted and must be masked. */
    val status: String? = "active"
)

/**
 * Feed/post domain model. Marked [Immutable] so the Compose compiler can skip
 * recomposition of post cards when the list is replaced with an equal copy.
 * All properties are derived from the immutable constructor params and are
 * precomputed once (instead of `get()`) to keep recompositions cheap.
 */
@Immutable
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
    val imageUrls: List<String> = mediaUrls.ifEmpty { fallbackImageUrls }
    val timeAgo: String = formatTimestamp(createdAt)

    /** 🌟 True when the author soft-closed their account: the UI masks them as
     *  "Removed User" with the generic avatar placeholder — but the @username
     *  stays visible (it remains locked to the removed user). */
    val isAuthorClosed: Boolean = authorProfile?.status == "closed"

    val firstName: String =
        if (isAuthorClosed) "Removed User"
        else authorProfile?.firstName?.ifBlank { null } ?: "User"
    val handle: String = authorProfile?.handle?.ifBlank { null } ?: "user"
    val userAvatarUrl: String? = if (isAuthorClosed) null else authorProfile?.avatarUrl

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
