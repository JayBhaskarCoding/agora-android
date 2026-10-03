package com.example.agora.data

import android.content.Context
import android.util.Log

/**
 * Remembers, on this device, which account had its email verified recently.
 *
 * WHY THIS EXISTS
 * ---------------
 * A freshly verified profile is a **placeholder** row — `handle_new_user()`
 * copies `first_name = "Pending"`, `last_name = "User"` and `handle = "user_xxxx"`
 * from the sign-up metadata, and those are only replaced when onboarding
 * finishes. The cold-start "abandoned account" cleanup in
 * [com.example.agora.viewmodel.AuthViewModel] keys off exactly that shape, so a
 * user who verified, had the process killed (or simply reopened the app later)
 * had a brand-new account permanently deleted — `delete_abandoned_user()` deletes
 * the row in `auth.users` and cascades — and was dropped back on Login.
 *
 * Remembering the verification lets a cold start **resume onboarding** instead of
 * wiping, while genuinely stale accounts (older than [GRACE_PERIOD_MS]) are still
 * cleaned up exactly as before.
 *
 * Call [init] once from [com.example.agora.AgoraApplication.onCreate].
 */
object RecentVerificationStore {

    private const val TAG = "RecentVerificationStore"
    private const val PREFS_NAME = "agora_verification"
    private const val KEY_USER_ID = "verified_user_id"
    private const val KEY_VERIFIED_AT = "verified_at_ms"

    /**
     * How long a just-verified account is shielded from the abandoned-account
     * cleanup. Tune here — nothing else reads the value.
     */
    const val GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L

    @Volatile
    private var appContext: Context? = null

    /** Must be called before [mark] / [isRecent] (Application.onCreate). */
    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun prefs() = (appContext
        ?: error("RecentVerificationStore.init(context) must be called from Application.onCreate"))
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records [userId] as verified just now. */
    fun mark(userId: String) {
        if (userId.isBlank()) return
        prefs().edit()
            .putString(KEY_USER_ID, userId)
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .apply()
        Log.d(
            TAG,
            "marked user=$userId as recently verified (grace ${GRACE_PERIOD_MS / 3_600_000}h)"
        )
    }

    /** True when [userId] was verified on this device within the grace period. */
    fun isRecent(userId: String): Boolean {
        if (userId.isBlank()) return false
        val storedUserId = prefs().getString(KEY_USER_ID, null) ?: return false
        val verifiedAt = prefs().getLong(KEY_VERIFIED_AT, 0L)
        if (verifiedAt <= 0L) return false

        val ageMs = System.currentTimeMillis() - verifiedAt
        val recent = storedUserId == userId && ageMs < GRACE_PERIOD_MS
        Log.d(
            TAG,
            "isRecent=$recent for user=$userId (stored=$storedUserId, age=${ageMs / 1000}s)"
        )
        return recent
    }

    /** Drops the record — onboarding finished, or the user signed out. */
    fun clear() {
        prefs().edit().clear().apply()
    }
}
