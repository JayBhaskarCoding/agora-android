package com.example.agora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String = "",

    // These default to null/empty because they aren't in the comments table directly.
    // We will merge them from the profiles table in the ViewModel!
    val userAvatarUrl: String? = null,
    val displayName: String = "User",
    val handle: String = "user"
)

@Serializable
data class CommentInsertRequest(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val content: String
)