package com.example.agora.navigation

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects Supabase / GoTrue **auth callbacks** arriving as deep links (email OTP
 * link, magic link, password recovery, OAuth) and remembers that fact for the
 * rest of the process.
 *
 * WHY THIS EXISTS
 * ---------------
 * [com.example.agora.MainActivity.onCreate] hands the incoming intent to
 * `supabaseClient.handleDeeplinks(intent)` *before* `setContent` runs, and
 * [com.example.agora.viewmodel.AuthViewModel] — which is what observes
 * `sessionStatus` — is only created inside `setContent`. A link that verifies
 * straight away therefore establishes the session **before the observer that
 * decides where to route exists**, so the ViewModel could not tell that session
 * apart from one restored from local storage at cold start. That distinction is
 * exactly what the abandoned-account cleanup keys off (see
 * [AuthViewModel.verifyProfileCompleteness]), and getting it wrong signed the
 * user straight back out to Login the moment they verified.
 *
 * The gate is deliberately sticky *and* observable:
 *  - sticky ([hasSeenAuthCallback]) so a callback handled before the ViewModel
 *    exists is still visible when its `init` runs;
 *  - observable ([callbackCount], a [StateFlow] that replays) so a callback that
 *    arrives later through `onNewIntent` also reaches the already-running
 *    ViewModel.
 */
object AuthCallbackGate {

    private const val TAG = "AuthCallbackGate"

    /**
     * Markers GoTrue puts on its callback URLs. Implicit flow carries the tokens
     * in the **fragment** (`…#access_token=…&refresh_token=…`), PKCE / OTP links
     * carry them in the **query** (`…?token_hash=…&type=signup`, `…?code=…`).
     */
    private val AUTH_MARKERS = listOf(
        "access_token",
        "refresh_token",
        "token_hash",
        "token_type",
        "expires_in",
        "login-callback",
        "auth-callback"
    )

    private val _callbackCount = MutableStateFlow(0)
    val callbackCount: StateFlow<Int> = _callbackCount.asStateFlow()

    /** True once any auth callback has been seen in this process. */
    fun hasSeenAuthCallback(): Boolean = _callbackCount.value > 0

    /**
     * Inspects [intent] and arms the gate when it carries an auth callback.
     * Call from `onCreate` (fresh starts only) and `onNewIntent` (always) —
     * always *before* `handleDeeplinks` so the flag is set no matter how fast
     * GoTrue establishes the session.
     */
    fun notify(intent: Intent?) {
        if (!isAuthCallback(intent)) return
        _callbackCount.value = _callbackCount.value + 1
        Log.d(
            TAG,
            "auth callback deep link detected — the session it creates belongs to this run " +
                "(uri=${intent?.data})"
        )
    }

    /** True when [intent] is a Supabase auth callback (as opposed to a post link). */
    fun isAuthCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW) return false

        val haystack = buildString {
            append(uri.host.orEmpty()).append(' ')
            append(uri.path.orEmpty()).append(' ')
            append(uri.query.orEmpty()).append(' ')
            append(uri.fragment.orEmpty())
        }.lowercase()

        return AUTH_MARKERS.any { haystack.contains(it) }
    }
}
