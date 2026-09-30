package com.example.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
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
    /** Single-device login claim: device id of the device currently allowed to hold the session. */
    @SerialName("current_device_id")
    val currentDeviceId: String? = null,
    val email: String? = null,
    /** 🌟 When non-null the account is in the 3-day deletion grace window: it
     *  will be processed at this instant by the backend cron job. */
    @SerialName("deletion_scheduled_at")
    val deletionScheduledAt: String? = null,
    /** 🌟 Chosen deletion mode while scheduled: 'soft' = close the account and
     *  keep posts under a "Removed User" alias, 'hard' = erase everything.
     *  NULL until the user picks one on the DeletionModeScreen. */
    @SerialName("deletion_mode")
    val deletionMode: String? = null,
    /** 'active' or 'closed' — closed accounts are soft-deleted and masked everywhere. */
    val status: String? = "active"
)
