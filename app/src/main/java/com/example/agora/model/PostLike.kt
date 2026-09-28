package com.example.agora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PostLike(
    @SerialName("user_id") val userId: String,
    @SerialName("post_id") val postId: String,
    @SerialName("reaction_type") val reactionType: String = "❤️"
)
