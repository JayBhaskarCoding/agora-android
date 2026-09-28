package com.example.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class PostLike(
    @SerialName("user_id") val userId: String,
    @SerialName("post_id") val postId: String,
    @SerialName("reaction_type") val reactionType: String = "❤️"
)
