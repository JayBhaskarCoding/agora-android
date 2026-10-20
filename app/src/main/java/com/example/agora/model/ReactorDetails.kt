package com.example.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ReactorDetails(
    @SerialName("user_id") val userId: String = "",
    val displayName: String = "User",
    val username: String = "@user",
    val avatarUrl: String? = null,
    @SerialName("reaction_type") val reactionType: String = "❤️"
)
