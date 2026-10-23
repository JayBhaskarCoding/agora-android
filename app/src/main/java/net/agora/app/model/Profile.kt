package net.agora.app.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Profile(
    val id: String = "",
    /**
     * 🌟 NULL, not `"User"`, when the column is NULL.
     *
     *   The old defaults were sentinels: a NULL `handle` decoded to the literal
     *   `"user"` and a missing `first_name` to `"User"`. Because the onboarding
     *   check compared against those exact strings, a *serialization default* was
     *   doing double duty as a real database value — which made "the row is empty"
     *   and "the user is called User" the same state.
     *
     *   Every optional column now decodes to null (`coerceInputValues = true`
     *   handles explicit JSON nulls for non-nullable types), so null means null.
     */
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val handle: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val gender: String? = null,
    val dob: String? = null,
    /** 🌟 When the user last completed an explicit Change Password / Reset
     *  Password flow. NULL means the password has never changed — a brand-new
     *  account always starts NULL and the UI renders "Never". */
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
