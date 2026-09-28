package com.example.agora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String = "",
    @SerialName("first_name") val firstName: String? = "User",
    @SerialName("last_name") val lastName: String? = null,
    val handle: String = "user",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val gender: String? = null,
    val dob: String? = null,
    @SerialName("password_changed_at")
    val passwordChangedAt: String? = null,
    @SerialName("active_session_id")
    val activeSessionId: String? = null,
    val email: String? = null
)
