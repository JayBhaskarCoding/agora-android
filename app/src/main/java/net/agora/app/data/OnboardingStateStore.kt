package net.agora.app.data

import android.content.Context
import android.util.Log

/**
 * ✦ REMEMBERS, ON THIS DEVICE, THAT ONBOARDING FINISHED — for one user id.
 *
 * WHY THIS EXISTS
 * ---------------
 * Before this, nothing recorded that a user had completed onboarding. The shell
 * decided Home vs "Create Your Profile" purely from whether `finishOnboarding()`
 * was called **in the current process**, and on the next start it re-derived the
 * answer from a single unguarded read of `public.profiles`.
 *
 * That is a bad trade for the one question involved. The cost of wrongly saying
 * "complete" is that a user sees the feed with an unfinished profile; the cost of
 * wrongly saying "incomplete" is that a user who already finished is marched back
 * through the wizard — or, with the cold-start cleanup, has their account deleted.
 * The asymmetry says: when the database read is inconclusive, believe the device.
 *
 * So this is a *tie-breaker*, not a source of truth. It never overrides a
 * confirmation either way:
 *
 *  - a real `profiles` row with a usable username still means COMPLETE even when
 *    this store is empty (a fresh install, a reinstalled app, another device);
 *  - an explicit placeholder row still means onboarding for an account with no
 *    record here.
 *
 * What it removes is the third case — "I could not read the row" — being silently
 * treated as "this user has no profile".
 *
 * Keyed by user id so switching accounts cannot leak a completion between them,
 * and cleared on sign-out ([clear]) so the next account starts clean.
 *
 * Call [init] once from [net.agora.app.AgoraApplication.onCreate].
 */
object OnboardingStateStore {

    private const val TAG = "OnboardingStateStore"
    private const val PREFS_NAME = "agora_onboarding"

    /** The user id onboarding was completed for. */
    private const val KEY_USER_ID = "completed_user_id"

    /** When it completed — diagnostics only, never used for expiry. */
    private const val KEY_COMPLETED_AT = "completed_at_ms"

    @Volatile
    private var appContext: Context? = null

    /** Must be called before [markComplete] / [isComplete] (Application.onCreate). */
    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun prefs() = (appContext
        ?: error("OnboardingStateStore.init(context) must be called from Application.onCreate"))
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records that onboarding finished for [userId].
     *
     * Called from two places on purpose: as soon as the profile row is confirmed
     * persisted (so killing the app on the theme step cannot undo it), and again
     * when the wizard closes. Idempotent.
     */
    fun markComplete(userId: String) {
        if (userId.isBlank()) return
        prefs().edit()
            .putString(KEY_USER_ID, userId)
            .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "onboarding recorded complete for user=$userId")
    }

    /**
     * True only when this device recorded a completion **for this user id**.
     *
     * A different id (an account switch) is not a match: the record is per-user,
     * not per-device.
     */
    fun isComplete(userId: String): Boolean {
        if (userId.isBlank()) return false
        val stored = prefs().getString(KEY_USER_ID, null) ?: return false
        return stored == userId
    }

    /** Drops the record — sign-out, or a deleted account. */
    fun clear() {
        prefs().edit().clear().apply()
    }
}
