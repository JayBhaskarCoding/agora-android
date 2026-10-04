package com.example.agora.viewmodel

import android.content.Context
import android.os.SystemClock
import com.example.agora.utils.PasswordPolicy
import com.example.agora.utils.PasswordResetRouting
import com.example.agora.utils.OtpCooldown
import android.util.Log
import android.util.Patterns
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agora.BuildConfig
import com.example.agora.data.EmailOtpGateway
import com.example.agora.data.EmailVerificationFlow
import com.example.agora.data.DeviceIdProvider
import com.example.agora.data.RecentVerificationStore
import com.example.agora.data.supabaseClient
import com.example.agora.model.Profile
import com.example.agora.navigation.AuthCallbackGate
import com.example.agora.service.SessionConflictRelay
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import android.net.Uri
import com.example.agora.utils.ImageUtils
import com.example.agora.utils.handleAppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

enum class PasswordResetStep { EMAIL, OTP, NEW_PASSWORD }

/** CHANGE = signed-in user changing a known password; RECOVERY = signed-out reset. */
enum class PasswordResetMode { CHANGE, RECOVERY }

/**
 * Where the auth shell ([com.example.agora.MainActivity]) must go once a session
 * exists.
 *
 * Emitted **explicitly** the moment a session is created — email OTP, magic
 * link, sign-up or Google — instead of leaving the destination to a race between
 * `sessionStatus` and the onboarding flag. Consuming it also drops the shell's
 * auth entry (Login / Register) so those screens can never be composed again
 * without an explicit sign-out.
 */
sealed interface AuthNavTarget {
    /** Verified, but the profile is still a placeholder — collect the details. */
    data object Onboarding : AuthNavTarget

    /** Verified and the profile is complete — straight to the feed. */
    data object Home : AuthNavTarget
}

/** Polling fallback for the single-device claim check (Realtime is the fast path). */
private const val DEVICE_CLAIM_POLL_INTERVAL_MS = 5_000L

class AuthViewModel : ViewModel() {

    private var activeSessionChannel: RealtimeChannel? = null
    private var sessionConflictJob: Job? = null

    /**
     * Set to true immediately before an explicit sign-in so that the next
     * [startDeviceSession] claims this device (latest login wins). Cold starts
     * never set it: they validate the existing claim instead of stealing it.
     */
    @Volatile
    private var claimDeviceOnNextSession = false

    val sessionStatus = supabaseClient.auth.sessionStatus

    private val _remoteLogoutEvent = MutableStateFlow(false)
    val remoteLogoutEvent: StateFlow<Boolean> = _remoteLogoutEvent.asStateFlow()

    private val _userState = MutableStateFlow<UserInfo?>(null)
    val userState: StateFlow<UserInfo?> = _userState.asStateFlow()

    // 🌟 Full profile row (handle, avatar_url, …) for the signed-in user —
    //    the single source of truth feeding the drawer header. Screens call
    //    [refreshProfile] whenever identity may have changed (route changes,
    //    avatar edits) and collect [profileState] reactively.
    private val _profileState = MutableStateFlow<Profile?>(null)
    val profileState: StateFlow<Profile?> = _profileState.asStateFlow()

    /** Re-fetches `profiles` for the current user; cheap enough to call on every route change. */
    fun refreshProfile() {
        val uid = supabaseClient.auth.currentUserOrNull()?.id
        if (uid == null) {
            _profileState.value = null
            return
        }
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles")
                        .select { filter { eq("id", uid) } }
                        .decodeSingle<Profile>()
                }
                _profileState.value = profile
            } catch (e: Exception) {
                Log.w("AuthViewModel", "refreshProfile failed: ${e.message}")
            }
        }
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _awaitingOtp = MutableStateFlow(false)
    val awaitingOtp: StateFlow<Boolean> = _awaitingOtp.asStateFlow()

    // 🌟 Email the pending OTP was sent to — displayed by the verification
    //    card and kept in sync with [pendingEmail]. Manual signups only:
    //    Google identities skip OTP entirely (Google verified the address),
    //    so this stays empty for OAuth sign-ins.
    private val _otpEmail = MutableStateFlow("")
    val otpEmail: StateFlow<String> = _otpEmail.asStateFlow()

    private var pendingEmail: String = ""

    private val emailVerification = EmailVerificationFlow(object : EmailOtpGateway {
        override suspend fun requestCode(email: String, createUser: Boolean) {
            supabaseClient.auth.signInWith(OTP) {
                this.email = email
                this.createUser = createUser
            }
        }

        override suspend fun verifyCode(email: String, code: String): String {
            supabaseClient.auth.verifyEmailOtp(
                type = OtpType.Email.EMAIL,
                email = email,
                token = code
            )
            return requireNotNull(supabaseClient.auth.currentUserOrNull()?.id) {
                "Email verification did not create a session."
            }
        }
    })

    private val _isEmailOtpBusy = MutableStateFlow(false)
    val isEmailOtpBusy: StateFlow<Boolean> = _isEmailOtpBusy.asStateFlow()

    // Monotonic time survives recomposition/activity recreation and cannot be
    // shortened by changing the device's wall clock. Supabase limits still apply.
    private val _emailOtpResendAvailableAt = MutableStateFlow(0L)
    val emailOtpResendAvailableAt: StateFlow<Long> = _emailOtpResendAvailableAt.asStateFlow()

    private val _passwordResetStep = MutableStateFlow<PasswordResetStep?>(null)
    val passwordResetStep: StateFlow<PasswordResetStep?> = _passwordResetStep.asStateFlow()

    private val _passwordResetMode = MutableStateFlow(PasswordResetMode.RECOVERY)
    val passwordResetMode: StateFlow<PasswordResetMode> = _passwordResetMode.asStateFlow()

    /** Absolute monotonic deadline for the emailed-code countdown (0 = expired). */
    private val _passwordResetResendAvailableAt = MutableStateFlow(0L)
    val passwordResetResendAvailableAt: StateFlow<Long> = _passwordResetResendAvailableAt.asStateFlow()

    /** True while a reset request, verification or password update is in flight. */
    private val _isPasswordResetBusy = MutableStateFlow(false)
    val isPasswordResetBusy: StateFlow<Boolean> = _isPasswordResetBusy.asStateFlow()

    /** True once Supabase accepted a recovery-code request for [recoveryEmail]. */
    private val _isRecoveryCodeRequested = MutableStateFlow(false)
    val isRecoveryCodeRequested: StateFlow<Boolean> = _isRecoveryCodeRequested.asStateFlow()

    /**
     * True only after `verifyEmailOtp(RECOVERY)` succeeded — i.e. Supabase
     * returned a session for the emailed code. The new-password step is gated on
     * this, so it can never be reached by skipping verification.
     */
    private val _isRecoverySessionVerified = MutableStateFlow(false)
    val isRecoverySessionVerified: StateFlow<Boolean> = _isRecoverySessionVerified.asStateFlow()

    private var recoveryEmail: String = ""

    private val _isOnboarding = MutableStateFlow(false)
    val isOnboarding: StateFlow<Boolean> = _isOnboarding.asStateFlow()

    private val _googleFirstName = MutableStateFlow("")
    val googleFirstName: StateFlow<String> = _googleFirstName.asStateFlow()

    private val _googleLastName = MutableStateFlow("")
    val googleLastName: StateFlow<String> = _googleLastName.asStateFlow()

    private val _googleAvatarUrl = MutableStateFlow<String?>(null)
    val googleAvatarUrl: StateFlow<String?> = _googleAvatarUrl.asStateFlow()

    private val _isCheckingProfileCompleteness = MutableStateFlow(true)
    val isCheckingProfileCompleteness: StateFlow<Boolean> = _isCheckingProfileCompleteness.asStateFlow()

    private var hasCompletedColdStartCheck = false

    /**
     * One-shot routing instruction for the auth shell. `replay = 1` so an event
     * emitted before the shell starts collecting (or after it is recreated by a
     * configuration change) is still delivered instead of vanishing.
     */
    private val _navEvent = MutableSharedFlow<AuthNavTarget>(replay = 1, extraBufferCapacity = 1)
    val navEvent: SharedFlow<AuthNavTarget> = _navEvent.asSharedFlow()

    /** Emits an explicit navigation instruction to the auth shell. */
    private fun sendNav(target: AuthNavTarget) {
        viewModelScope.launch { _navEvent.emit(target) }
    }

    /**
     * True when the current session was established **in this app run** — sign-up,
     * email OTP, magic link, password recovery or login — as opposed to one
     * restored from local storage at cold start.
     *
     * The abandoned-account cleanup in [verifyProfileCompleteness] must never run
     * against such a session: a freshly verified profile is a placeholder row by
     * design (`first_name = "Pending"`, `handle = "user_…"`) until onboarding
     * finishes, and wiping it deleted the account and bounced the user back to
     * Login immediately after they verified.
     */
    @Volatile
    private var sessionCreatedInThisRun = false

    private val resendTimestamps = mutableListOf<Long>()

    init {
        // ★ A deep link can create the session BEFORE this ViewModel exists:
        //   MainActivity hands the intent to handleDeeplinks() in onCreate(), and
        //   setContent() — where this ViewModel is built — only runs afterwards.
        //   The gate is sticky, so the callback is still visible here; the StateFlow
        //   also replays it into the collector below for links that arrive later
        //   through onNewIntent().
        sessionCreatedInThisRun = AuthCallbackGate.hasSeenAuthCallback()

        viewModelScope.launch {
            AuthCallbackGate.callbackCount.collect {
                sessionCreatedInThisRun = true
            }
        }

        // Global session-revoked events (Realtime or polling) -> flag for the
        // root UI to show the mandatory dialog. The session itself is only
        // cleared after the user acknowledges it ([confirmRemoteLogout]).
        viewModelScope.launch {
            SessionConflictRelay.events.collect {
                stopDeviceListeners()
                _remoteLogoutEvent.value = true
            }
        }

        viewModelScope.launch {
            val currentUser = supabaseClient.auth.currentUserOrNull()
            _userState.value = currentUser

            currentUser?.id?.let { userId ->
                startDeviceSession(userId)
            }

            supabaseClient.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        _userState.value = user
                        user?.id?.let { userId ->
                            startDeviceSession(userId)
                            // Verify profile completeness to guard against abandoned onboarding on cold start
                            verifyProfileCompleteness(userId)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        // Settled as signed out: from here on any session can only
                        // have been created in this run (sign-in, sign-up or
                        // verification) — never restored from storage at cold start.
                        sessionCreatedInThisRun = true
                        _userState.value = null
                        _isCheckingProfileCompleteness.value = false
                    }
                    else -> {
                        _isCheckingProfileCompleteness.value = false
                    }
                }
            }
        }
    }

    /**
     * Single source of truth for "is this account still in an onboarding
     * state?". Reads the row persisted by [saveOnboardingDetails] from the
     * `profiles` table — auth user_metadata is NOT authoritative (database
     * updates never touch it, and Google sign-ups create a trigger-made row
     * with NULLs). Logs the full verdict so re-login loops can be diagnosed
     * from Logcat alone (tag: AuthDiagnostics).
     */
    private fun isProfileIncomplete(userId: String, profile: Profile?, source: String): Boolean {
        // 🌟 gender and dob are OPTIONAL: users routinely skip them in onboarding and
        //    saveOnboardingDetails persists NULL. They must NOT mark an otherwise
        //    finished profile as incomplete — that trapped returning users in a
        //    re-login onboarding loop (cold start even wiped their account).
        //
        //    The remaining checks only ever match placeholder rows:
        //     - email signup: first_name="Pending", last_name="User", handle="user_xxxx"
        //     - Google signup (trigger-made row before onboarding): first_name/last_name
        //       NULL and handle NULL -> decoded as null / "user" via the Profile defaults
        //    Step 1 of the onboarding UI gates on firstName+handle being non-blank, so a
        //    legitimately completed profile can never fail these checks.
        val incomplete = profile == null ||
                profile.firstName.isNullOrBlank() ||
                profile.firstName == "Pending" ||
                profile.lastName == "User" ||
                profile.handle.isBlank() ||
                profile.handle == "user" ||
                profile.handle.startsWith("user_")

        Log.d(
            "AuthDiagnostics",
            "[$source] currentUser.id=$userId | profiles row=" +
                (if (profile == null) {
                    "NULL (no row returned!)"
                } else {
                    "firstName=${profile.firstName} lastName=${profile.lastName} " +
                        "handle=${profile.handle} gender=${profile.gender} dob=${profile.dob} " +
                        "email=${if (profile.email.isNullOrBlank()) "NULL" else "present"}"
                }) +
                " | verdict=${if (incomplete) "INCOMPLETE -> onboarding" else "COMPLETE -> feed"}"
        )
        return incomplete
    }

    private fun verifyProfileCompleteness(userId: String) {
        // Cold-start guard: If cold-start check has already completed, bypass to prevent race conditions during active registration
        if (hasCompletedColdStartCheck) {
            return
        }

        // If user is actively completing onboarding in this session, bypass
        if (_isOnboarding.value) {
            _isCheckingProfileCompleteness.value = false
            hasCompletedColdStartCheck = true
            return
        }

        /* ★ POST-VERIFICATION GUARD — the "verified, then dumped back on Login" bug.
         *
         *   Two situations reach this point with a placeholder profile row and are
         *   NOT abandoned accounts:
         *     1. the session was created in this run — in-app OTP, magic link,
         *        sign-up or login ([sessionCreatedInThisRun]);
         *     2. the email was verified on this device within
         *        RecentVerificationStore.GRACE_PERIOD_MS, e.g. the user verified,
         *        the process was killed and the session is now being restored.
         *
         *   Both must RESUME ONBOARDING. Falling through to the abandonment wipe
         *   below deletes the auth user (delete_abandoned_user() cascades to
         *   profiles and posts) and signs the user out — which is precisely the
         *   reported behaviour: verify → back to Login, account gone. */
        if (sessionCreatedInThisRun || RecentVerificationStore.isRecent(userId)) {
            Log.d(
                "AuthDiagnostics",
                "[postAuth] sessionCreatedInThisRun=$sessionCreatedInThisRun " +
                    "recentVerification=${RecentVerificationStore.isRecent(userId)} " +
                    "-> resolving destination without any abandonment wipe"
            )
            resolveAfterAuthentication(userId, "postAuth")
            return
        }

        viewModelScope.launch {
            try {
                _isCheckingProfileCompleteness.value = true
                hasCompletedColdStartCheck = true
                Log.d("AuthDiagnostics", "[coldStart] querying profiles by id for currentUser.id=$userId")

                val profile = withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles")
                        .select { filter { eq("id", userId) } }
                        .decodeSingleOrNull<Profile>()
                }

                if (isProfileIncomplete(userId, profile, "coldStart")) {
                    // ABANDONMENT WIPE: Cold start detected an abandoned session from a previous run
                    Log.w("AuthDiagnostics", "[coldStart] incomplete profile — wiping abandoned user=$userId")
                    try {
                        supabaseClient.postgrest.rpc("delete_abandoned_user")
                    } catch (_: Exception) {}
                    signOut()
                } else {
                    _isOnboarding.value = false
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(
                    "AuthDiagnostics",
                    "[coldStart] profile check FAILED for user=$userId (network/RLS?) — signing out to be safe: ${e.localizedMessage}",
                    e
                )
                signOut()
            } finally {
                _isCheckingProfileCompleteness.value = false
            }
        }
    }

    /**
     * Routes a freshly authenticated user — onboarding while the profile row is
     * still a placeholder, the feed once it is complete — and emits the matching
     * [AuthNavTarget] so the shell navigates **explicitly** after a successful
     * verification instead of waiting for reactive state to settle.
     *
     * Unlike [verifyProfileCompleteness] this NEVER wipes the account: the session
     * was just established (or the email was verified on this device moments ago),
     * so an incomplete profile means "onboarding has not run yet" — not
     * "abandoned". A transient read failure also defaults to onboarding rather
     * than destroying a live session.
     */
    private fun resolveAfterAuthentication(userId: String, source: String) {
        viewModelScope.launch {
            try {
                _isCheckingProfileCompleteness.value = true
                hasCompletedColdStartCheck = true
                Log.d("AuthDiagnostics", "[$source] querying profiles by id for currentUser.id=$userId")

                val profile = withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles")
                        .select { filter { eq("id", userId) } }
                        .decodeSingleOrNull<Profile>()
                }

                val incomplete = isProfileIncomplete(userId, profile, source)
                _isOnboarding.value = incomplete
                _isCheckingProfileCompleteness.value = false

                if (incomplete) {
                    // Shield the account across process death: a cold start before
                    // onboarding finishes must resume, never wipe.
                    RecentVerificationStore.mark(userId)
                }
                sendNav(if (incomplete) AuthNavTarget.Onboarding else AuthNavTarget.Home)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(
                    "AuthDiagnostics",
                    "[$source] profile check FAILED for user=$userId — defaulting to onboarding " +
                        "(a live session is never wiped on a transient error): ${e.localizedMessage}",
                    e
                )
                _isOnboarding.value = true
                _isCheckingProfileCompleteness.value = false
                RecentVerificationStore.mark(userId)
                sendNav(AuthNavTarget.Onboarding)
            }
        }
    }

    /** All user-facing failure copy comes from the centralized mapper in
     *  utils/ErrorHelper.kt (typed AuthErrorCodes, real HTTP statuses, SQLSTATEs
     *  — never raw backend text). Kept as a private delegate so the ~15 existing
     *  call sites and their state flows remain untouched. */
    private fun handleAuthError(e: Throwable): String = handleAppError(e)

    fun resendOtp() {
        if (_isEmailOtpBusy.value || SystemClock.elapsedRealtime() < _emailOtpResendAvailableAt.value) return
        val now = System.currentTimeMillis()
        val thirtyMinsInMillis = 30 * 60 * 1000L

        resendTimestamps.removeAll { now - it > thirtyMinsInMillis }

        if (resendTimestamps.size >= 3) {
            _errorMessage.value = "Maximum attempts reached. Please wait 30 minutes before trying again."
            return
        }

        if (pendingEmail.isBlank()) {
            _errorMessage.value = "No pending email verification found."
            return
        }

        _isEmailOtpBusy.value = true
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) { emailVerification.resend() }
                _emailOtpResendAvailableAt.value = SystemClock.elapsedRealtime() + OtpCooldown.DURATION_MS
                resendTimestamps.add(now)
                _errorMessage.value = "Verification code requested. Please check your inbox and spam folder."
                Log.d("AuthDiagnostics", "[emailOtp] resend accepted")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AuthDiagnostics", "[emailOtp] resend failed", e)
                _errorMessage.value = handleAuthError(e)
            } finally {
                _isEmailOtpBusy.value = false
            }
        }
    }

    /** Back/edit only clears the local challenge; it never verifies or deletes an account. */
    fun cancelEmailVerification() {
        if (_isEmailOtpBusy.value) return
        emailVerification.cancel()
        pendingEmail = ""
        _otpEmail.value = ""
        _awaitingOtp.value = false
        clearError()
    }

    // =====================================================================================
    // SINGLE-DEVICE LOGIN POLICY
    //
    // profiles.current_device_id holds the device id (UUID persisted on-device) of the
    // device that currently owns the session. Explicit logins claim the column (latest
    // login wins); every running device watches the column (Supabase Realtime + polling
    // fallback) and raises a global SessionConflictRelay event when it no longer matches.
    // =====================================================================================

    private suspend fun claimDeviceSession(userId: String) {
        withContext(Dispatchers.IO) {
            try {
                supabaseClient.from("profiles").update(
                    mapOf("current_device_id" to DeviceIdProvider.deviceId)
                ) {
                    filter { eq("id", userId) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    private suspend fun fetchRemoteDeviceId(userId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                supabaseClient.from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()
                    ?.currentDeviceId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    /**
     * Reads a nullable string field from a Realtime record. JsonNull must map to
     * null — [JsonNull] is a JsonPrimitive whose content is the literal "null",
     * which would otherwise fake a device mismatch when a claim is released.
     */
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /**
     * Starts (or resumes) the single-device session for [userId].
     *
     * - After an explicit sign-in ([claimDeviceOnNextSession] == true) this device
     *   claims `current_device_id` — kicking any other device.
     * - On a cold start the claim is only adopted when the column is empty (legacy
     *   rows); a foreign claim raises the session-revoked event instead of stealing it.
     */
    private fun startDeviceSession(userId: String) {
        if (sessionConflictJob?.isActive == true) return

        sessionConflictJob = viewModelScope.launch {
            if (claimDeviceOnNextSession) {
                claimDeviceOnNextSession = false
                claimDeviceSession(userId)
            } else {
                val remoteDeviceId = fetchRemoteDeviceId(userId)
                when {
                    remoteDeviceId.isNullOrBlank() -> claimDeviceSession(userId)
                    remoteDeviceId != DeviceIdProvider.deviceId -> {
                        // Account is owned by another device right now.
                        SessionConflictRelay.notifySessionRevoked()
                        return@launch
                    }
                }
            }
            listenForDeviceChanges(userId)
        }
    }

    /** Realtime (postgres_changes) + polling fallback on the user's profiles row. */
    private suspend fun listenForDeviceChanges(userId: String) {
        coroutineScope {
            // Fast path: Supabase Realtime on this user's row.
            launch(Dispatchers.IO) {
                try {
                    supabaseClient.realtime.connect()
                    val channel = supabaseClient.channel("device_session_$userId")
                    activeSessionChannel = channel

                    val changeFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                        table = "profiles"
                        filter("id", FilterOperator.EQ, userId)
                    }

                    channel.subscribe()

                    changeFlow.collect { action ->
                        val remoteDeviceId = action.record.stringOrNull("current_device_id")
                        if (!remoteDeviceId.isNullOrBlank() && remoteDeviceId != DeviceIdProvider.deviceId) {
                            SessionConflictRelay.notifySessionRevoked()
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                }
            }

            // Safety net: polling covers Realtime outages / restricted websockets.
            launch {
                while (isActive) {
                    delay(DEVICE_CLAIM_POLL_INTERVAL_MS)
                    val remoteDeviceId = fetchRemoteDeviceId(userId)
                    if (!remoteDeviceId.isNullOrBlank() && remoteDeviceId != DeviceIdProvider.deviceId) {
                        SessionConflictRelay.notifySessionRevoked()
                        break
                    }
                }
            }

            // Both children run until the parent session job is cancelled
            // ([stopDeviceListeners] / signOut). If Realtime dies, polling keeps
            // guarding; coroutineScope suspends until both are done.
        }
    }

    private fun stopDeviceListeners() {
        val previousJob = sessionConflictJob
        val previousChannel = activeSessionChannel
        sessionConflictJob = null
        activeSessionChannel = null

        viewModelScope.launch(Dispatchers.IO) {
            previousJob?.cancel()
            try {
                previousChannel?.unsubscribe()
            } catch (_: Exception) {}
        }
    }

    /**
     * Called by the root UI once the user acknowledged the
     * "You have been logged in on another device" dialog. Clears the session and
     * all local user state; MainActivity then routes back to Login.
     */
    fun confirmRemoteLogout() {
        _remoteLogoutEvent.value = false
        signOut()
    }

    fun clearRemoteLogoutFlag() {
        _remoteLogoutEvent.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun finishOnboarding() {
        hasCompletedColdStartCheck = true
        _isCheckingProfileCompleteness.value = false
        _isOnboarding.value = false
        // Profile is complete — the account no longer needs the cold-start shield.
        RecentVerificationStore.clear()
        // ★ Explicit route to the feed now that onboarding is done.
        sendNav(AuthNavTarget.Home)
    }

    /**
     * Entry point for *Change Password* (authenticated). Requires a live
     * session; without one it degrades to the strict recovery flow rather than
     * opening the new-password step unverified.
     */
    fun startChangePassword() {
        if (supabaseClient.auth.currentUserOrNull() == null) {
            startPasswordRecovery()
            return
        }
        _passwordResetMode.value = PasswordResetMode.CHANGE
        _isRecoveryCodeRequested.value = false
        _isRecoverySessionVerified.value = false
        _passwordResetResendAvailableAt.value = 0L
        _passwordResetStep.value = PasswordResetRouting.startStep(PasswordResetMode.CHANGE)
        clearError()
    }

    /**
     * Entry point for *Forgot password?* (unauthenticated). ALWAYS the strict
     * three-step sequence — a leftover session from an earlier attempt must
     * never let the user jump to the new-password screen.
     */
    fun startPasswordRecovery() {
        _passwordResetMode.value = PasswordResetMode.RECOVERY
        _isRecoveryCodeRequested.value = false
        _isRecoverySessionVerified.value = false
        _passwordResetResendAvailableAt.value = 0L
        _passwordResetStep.value = PasswordResetRouting.startStep(PasswordResetMode.RECOVERY)
        recoveryEmail = ""
        clearError()
    }

    /** Back from the code step to the email step (recovery only). */
    fun editRecoveryEmail() {
        if (_passwordResetMode.value != PasswordResetMode.RECOVERY) return
        if (_isPasswordResetBusy.value) return
        _isRecoveryCodeRequested.value = false
        _isRecoverySessionVerified.value = false
        _passwordResetResendAvailableAt.value = 0L
        _passwordResetStep.value = PasswordResetStep.EMAIL
        clearError()
    }

    fun requestPasswordResetOtp(email: String) {
        val cleanEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }
        if (_isPasswordResetBusy.value) return
        if (_passwordResetMode.value != PasswordResetMode.RECOVERY) return
        // A resend must respect the same 60-second window as the shared OTP card;
        // the very first send (EMAIL step) is never gated by it.
        if (_passwordResetStep.value == PasswordResetStep.OTP &&
            SystemClock.elapsedRealtime() < _passwordResetResendAvailableAt.value
        ) {
            return
        }

        _isPasswordResetBusy.value = true
        viewModelScope.launch {
            try {
                clearError()

                // Active Session Email Validation (if user is currently logged in)
                val currentSessionUser = supabaseClient.auth.currentUserOrNull()
                if (currentSessionUser != null && currentSessionUser.email != null) {
                    val activeSessionEmail = currentSessionUser.email!!
                    if (!cleanEmail.equals(activeSessionEmail, ignoreCase = true)) {
                        _errorMessage.value = "This email does not match your active account."
                        return@launch
                    }
                }

                // Pre-verification check: Ensure an account with this email exists in Supabase database
                val existingProfiles = withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles")
                        .select { filter { eq("email", cleanEmail) } }
                        .decodeList<Profile>()
                }

                if (existingProfiles.isEmpty()) {
                    _errorMessage.value = "No account found with this email address."
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    supabaseClient.auth.resetPasswordForEmail(cleanEmail)
                }
                recoveryEmail = cleanEmail
                _passwordResetResendAvailableAt.value =
                    SystemClock.elapsedRealtime() + OtpCooldown.DURATION_MS
                _isRecoveryCodeRequested.value = true
                _passwordResetStep.value = PasswordResetStep.OTP
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            } finally {
                _isPasswordResetBusy.value = false
            }
        }
    }

    fun verifyPasswordResetOtp(otpCode: String) {
        // Ordering guard: the code step exists only after a code was requested.
        // Nothing downstream may open the new-password screen without this.
        if (!PasswordResetRouting.canOpenOtpStep(
                _passwordResetMode.value,
                _isRecoveryCodeRequested.value
            )
        ) {
            _errorMessage.value = "Request a reset code for your email first."
            return
        }
        val cleanCode = otpCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it in '0'..'9' }) {
            _errorMessage.value = "Please enter the 6-digit code from your email."
            return
        }
        if (_isPasswordResetBusy.value) return

        _isPasswordResetBusy.value = true
        viewModelScope.launch {
            try {
                clearError()
                // ★ A recovery link mints a session too — keep the abandoned-account
                //   cleanup away from it.
                sessionCreatedInThisRun = true
                withContext(Dispatchers.IO) {
                    // Returns the AuthResponse that installs the temporary
                    // recovery session; a failure throws and leaves the code
                    // step open for a retry.
                    supabaseClient.auth.verifyEmailOtp(
                        type = OtpType.Email.RECOVERY,
                        email = recoveryEmail,
                        token = cleanCode
                    )
                }
                _isRecoverySessionVerified.value = true
                _passwordResetStep.value = PasswordResetStep.NEW_PASSWORD
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            } finally {
                _isPasswordResetBusy.value = false
            }
        }
    }

    fun submitNewPassword(newPassword: String, onSuccess: () -> Unit) {
        if (!PasswordResetRouting.canOpenNewPasswordStep(
                _passwordResetMode.value,
                _isRecoverySessionVerified.value
            )
        ) {
            _errorMessage.value = "Verify the code we emailed you before setting a new password."
            return
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            _errorMessage.value = PasswordPolicy.ERROR_MESSAGE
            return
        }
        if (_isPasswordResetBusy.value) return

        _isPasswordResetBusy.value = true
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.auth.updateUser {
                        password = newPassword
                    }

                    val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
                    if (currentUserId != null) {
                        supabaseClient.from("profiles").update(
                            mapOf("password_changed_at" to Instant.now().toString())
                        ) {
                            filter { eq("id", currentUserId) }
                        }
                    }
                }

                if (_passwordResetMode.value == PasswordResetMode.RECOVERY) {
                    // ★ Recovery is a *temporary* session. Ending it here drops
                    //   the user back on Login, exactly like popping the auth
                    //   back stack — and stops a later "Forgot password?" tap
                    //   from ever being treated as an authenticated change.
                    withContext(Dispatchers.IO) { supabaseClient.auth.signOut() }
                }
                recoveryEmail = ""
                _isRecoveryCodeRequested.value = false
                _isRecoverySessionVerified.value = false
                _passwordResetResendAvailableAt.value = 0L
                _passwordResetStep.value = null
                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            } finally {
                _isPasswordResetBusy.value = false
            }
        }
    }

    fun cancelPasswordReset() {
        _passwordResetStep.value = null
        _passwordResetResendAvailableAt.value = 0L
        _isPasswordResetBusy.value = false
        _isRecoveryCodeRequested.value = false
        _isRecoverySessionVerified.value = false
        recoveryEmail = ""
        clearError()
    }

    fun signIn(identifierInput: String, passwordInput: String) {
        viewModelScope.launch {
            clearError()
            val input = identifierInput.trim()
            if (input.isBlank() || passwordInput.isBlank()) {
                _errorMessage.value = "Please enter both email/username and password."
                return@launch
            }

            // 🌟 GHOST ACCOUNT INTERCEPT: an identifier belonging to a soft-closed
            //    account (its locked username, or the email it was closed with) gets
            //    a clear message instead of GoTrue's generic invalid-credentials
            //    error — the ghost's auth email was detached to a @ghost.agora dummy.
            try {
                val closed = withContext(Dispatchers.IO) {
                    supabaseClient.postgrest
                        .rpc(
                            "check_closed_account_login",
                            buildJsonObject { put("p_identifier", input) }
                        )
                        .decodeAs<Boolean>()
                }
                if (closed) {
                    _errorMessage.value = "This account is closed. You must register a new account to use this email."
                    return@launch
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Fail open — an unreachable check must not lock out live accounts.
                Log.w("AuthViewModel", "closed-account pre-login check failed: ${e.message}")
            }

            var loginEmail = input

            // 🌟 USERNAME → EMAIL RESOLUTION: Supabase signInWith(Email) requires an
            //    email address. If the identifier is a username/handle, resolve the
            //    account's email from public.profiles first (populated during
            //    onboarding and by the handle_new_user trigger + backfill in
            //    20990101000003_notifications_select_rls_and_profile_email.sql).
            if (!Patterns.EMAIL_ADDRESS.matcher(loginEmail).matches()) {
                val handleToSearch = loginEmail.removePrefix("@")
                Log.d("AuthViewModel", "signIn: '$handleToSearch' is not an email — resolving handle → email via profiles")
                try {
                    val response = withContext(Dispatchers.IO) {
                        supabaseClient.from("profiles")
                            .select { filter { eq("handle", handleToSearch) } }
                            .decodeSingleOrNull<JsonObject>()
                            ?: run {
                                // Case-insensitive retry (handles are user-typed); wildcards
                                // are escaped so '%'/'_' can't broaden the match.
                                val escaped = handleToSearch
                                    .replace("%", "\\%")
                                    .replace("_", "\\_")
                                supabaseClient.from("profiles")
                                    .select { filter { ilike("handle", escaped) } }
                                    .decodeSingleOrNull<JsonObject>()
                            }
                    }

                    val foundEmail = response?.stringOrNull("email")
                    if (foundEmail != null) {
                        loginEmail = foundEmail
                        Log.d("AuthViewModel", "signIn: resolved handle '$handleToSearch' → email on file; proceeding to password auth")
                    } else if (response != null) {
                        Log.w("AuthViewModel", "signIn: handle '$handleToSearch' found but profiles.email is NULL — cannot resolve login email")
                        _errorMessage.value = "This account has no email on record yet. Please sign in with Google instead."
                        return@launch
                    } else {
                        Log.w("AuthViewModel", "signIn: no profile found with handle '$handleToSearch'")
                        _errorMessage.value = "Username not found."
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("AuthViewModel", "signIn: handle → email lookup failed: ${e.localizedMessage}", e)
                    _errorMessage.value = handleAuthError(e)
                    return@launch
                }
            }

            try {
                // Claim the device BEFORE the session flips so the session-status
                // collector cannot mistake the previous device's claim for a conflict.
                claimDeviceOnNextSession = true
                supabaseClient.auth.signInWith(Email) {
                    email = loginEmail
                    password = passwordInput
                }
            } catch (e: Exception) {
                claimDeviceOnNextSession = false
                _errorMessage.value = handleAuthError(e)
                return@launch
            }

            try {
                val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return@launch
                // Covers the case where the session-status collector hasn't started the
                // device session yet (or the session was already active). Idempotent.
                claimDeviceOnNextSession = true
                startDeviceSession(userId)
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Login succeeded, but session sync failed: ${e.localizedMessage}"
            }
        }
    }

    // 🌟 GOOGLE SIGN-IN VIA CREDENTIAL MANAGER & SUPABASE AUTH
    fun signInWithGoogle(
        context: Context,
        webClientId: String = "925021938686-kqbu0iv5q4gkkoq1sqbnmrtk1uj83itk.apps.googleusercontent.com",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                clearError()
                val credentialManager = CredentialManager.create(context)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val token = googleIdTokenCredential.idToken

                    // 🌟 CRITICAL: Authenticate with Supabase Auth using Google ID Token via IDToken provider
                    claimDeviceOnNextSession = true
                    hasCompletedColdStartCheck = true
                    sessionCreatedInThisRun = true
                    withContext(Dispatchers.IO) {
                        supabaseClient.auth.signInWith(IDToken) {
                            idToken = token
                            provider = Google
                        }
                    }

                    val currentUser = supabaseClient.auth.currentUserOrNull()
                    val userId = currentUser?.id
                    if (userId != null) {
                        // 🌟 Differentiating Login vs Registration: Query profiles table
                        //    (user_metadata is never consulted — DB inserts don't update it).
                        var profileQueryFailed = false
                        val profile = withContext(Dispatchers.IO) {
                            try {
                                supabaseClient.from("profiles")
                                    .select { filter { eq("id", userId) } }
                                    .decodeSingleOrNull<Profile>()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                profileQueryFailed = true
                                Log.e(
                                    "AuthDiagnostics",
                                    "[googleSignIn] profiles query FAILED for user=$userId: ${e.localizedMessage}",
                                    e
                                )
                                null
                            }
                        }

                        if (profileQueryFailed) {
                            // Transient failure (network/RLS): do NOT force the user back
                            // through onboarding — treat as an existing account. The cold-start
                            // verifier and the next login will re-check completeness.
                            Log.w("AuthDiagnostics", "[googleSignIn] treating user=$userId as existing (query failed, not incomplete)")
                            _isOnboarding.value = false
                            claimDeviceOnNextSession = true
                            startDeviceSession(userId)
                            sendNav(AuthNavTarget.Home)
                        } else if (isProfileIncomplete(userId, profile, "googleSignIn")) {
                            Log.d("GoogleAuth", "New Google user or incomplete profile. Directing to Onboarding...")
                            val metadata = currentUser.userMetadata
                            val fullName = metadata?.get("full_name")?.jsonPrimitive?.content
                                ?: metadata?.get("name")?.jsonPrimitive?.content
                                ?: ""
                            val givenName = metadata?.get("given_name")?.jsonPrimitive?.content
                            val familyName = metadata?.get("family_name")?.jsonPrimitive?.content

                            val parsedFirstName = givenName?.ifBlank { null }
                                ?: fullName.trim().split("\\s+".toRegex()).firstOrNull()?.ifBlank { null }
                                ?: ""
                            val parsedLastName = familyName?.ifBlank { null }
                                ?: fullName.trim().split("\\s+".toRegex()).drop(1).joinToString(" ").ifBlank { null }
                                ?: ""

                            _googleFirstName.value = parsedFirstName
                            _googleLastName.value = parsedLastName

                            val avatarUrl = metadata?.get("picture")?.jsonPrimitive?.content
                                ?: metadata?.get("avatar_url")?.jsonPrimitive?.content
                            _googleAvatarUrl.value = avatarUrl?.ifBlank { null }

                            // 🌟 No OTP for Google identities: Google already
                            //    verified the address, so we never send a code
                            //    email or magic link. The parsed name/avatar
                            //    above prefill the enter-details screen, which
                            //    we route into straight away.
                            pendingEmail = currentUser.email
                                ?: metadata?.get("email")?.jsonPrimitive?.content
                                ?: ""
                            _isOnboarding.value = true
                            sendNav(AuthNavTarget.Onboarding)
                        } else {
                            Log.d("GoogleAuth", "Existing Google user with complete profile. Proceeding to Feed...")
                            _isOnboarding.value = false
                            // Claim this device (latest login wins) and start conflict listeners.
                            claimDeviceOnNextSession = true
                            startDeviceSession(userId)
                            sendNav(AuthNavTarget.Home)
                        }
                    }

                    onSuccess()
                } else {
                    _errorMessage.value = "Unexpected credential type received"
                }
            } catch (e: GetCredentialException) {
                if (e is NoCredentialException) {
                    _errorMessage.value = "No Google accounts available on device"
                } else if (e is GetCredentialCancellationException) {
                    // User backed out of the account picker — not an alarming error.
                    _errorMessage.value = "Google Sign-In was canceled."
                } else {
                    _errorMessage.value = handleAppError(e)
                }
                Log.e("GoogleAuth", "CredentialManager error: ${e.localizedMessage}", e)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAppError(e)
                Log.e("GoogleAuth", "Google sign-in error: ${e.localizedMessage}", e)
            }
        }
    }

    /** 🌟 Ghost intercept (registration) — true when this email once belonged to a
     *  soft-closed account whose posts were kept under the "Removed User" alias.
     *  Anon-callable RPC (check_closed_account_email matches the SHA-256 ghost
     *  hash stored at close time). Fails OPEN: if the lookup itself errors we
     *  proceed with the normal registration flow rather than block signups. */
    fun checkClosedAccountEmail(email: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val closed = withContext(Dispatchers.IO) {
                    supabaseClient.postgrest
                        .rpc(
                            "check_closed_account_email",
                            buildJsonObject { put("p_email", email.trim()) }
                        )
                        .decodeAs<Boolean>()
                }
                onResult(closed)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("AuthViewModel", "checkClosedAccountEmail failed: ${e.message}")
                onResult(false)
            }
        }
    }

    /** 🌟 The "Proceed" hammer of the registration ghost dialog — permanently
     *  deletes the closed account's kept posts (the RPC removes the profile row,
     *  cascading posts/comments/likes/notifications/reports) before the fresh
     *  signup fires. */
    fun wipeHistoricGhostData(email: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.postgrest.rpc(
                        "wipe_historic_ghost_data",
                        buildJsonObject { put("p_email", email.trim()) }
                    )
                }
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun startRegistration(emailInput: String) {
        if (_isEmailOtpBusy.value) return
        val cleanEmail = emailInput.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }

        _isEmailOtpBusy.value = true
        viewModelScope.launch {
            try {
                clearError()
                // /otp sends a code for both new and existing users. /signup can
                // auto-confirm, or return an obfuscated user without sending mail.
                Log.d("AuthDiagnostics", "[emailOtp] requesting /auth/v1/otp host=${Uri.parse(BuildConfig.SUPABASE_URL).host}")
                withContext(Dispatchers.IO) { emailVerification.request(cleanEmail) }
                _emailOtpResendAvailableAt.value = SystemClock.elapsedRealtime() + OtpCooldown.DURATION_MS
                pendingEmail = cleanEmail
                _otpEmail.value = cleanEmail
                _awaitingOtp.value = true
                Log.d("AuthDiagnostics", "[emailOtp] request accepted; awaiting verification")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AuthDiagnostics", "[emailOtp] request failed", e)
                _errorMessage.value = handleAuthError(e)
            } finally {
                _isEmailOtpBusy.value = false
            }
        }
    }

    fun verifyOtpCode(otpCode: String) {
        if (_isEmailOtpBusy.value) return
        val cleanCode = otpCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it in '0'..'9' }) {
            _errorMessage.value = "Please enter the 6-digit verification code."
            return
        }
        if (emailVerification.pendingEmail == null) {
            _errorMessage.value = "No pending email verification found. Please request a new code."
            return
        }

        _isEmailOtpBusy.value = true
        viewModelScope.launch {
            try {
                clearError()
                // Set this BEFORE verification: the session collector can fire
                // inside the request and must never wipe a placeholder profile.
                sessionCreatedInThisRun = true
                val verifiedUserId = withContext(Dispatchers.IO) {
                    emailVerification.verify(cleanCode)
                }
                _isCheckingProfileCompleteness.value = true
                _awaitingOtp.value = false
                pendingEmail = ""
                _otpEmail.value = ""
                hasCompletedColdStartCheck = true
                RecentVerificationStore.mark(verifiedUserId)

                // Returning users may already have a complete profile. Decide
                // from the persisted profile instead of forcing onboarding.
                claimDeviceOnNextSession = true
                startDeviceSession(verifiedUserId)
                resolveAfterAuthentication(verifiedUserId, "emailOtpVerified")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AuthDiagnostics", "[emailOtp] verification failed", e)
                _errorMessage.value = handleAuthError(e)
            } finally {
                _isEmailOtpBusy.value = false
            }
        }
    }

    // =====================================================================================
    // 🌟 CHANGE USERNAME — LOCAL EMAIL GATE + update_username RPC
    // =====================================================================================

    /** Step 1 — the identity gate, PURELY LOCAL per the guardrail: compares the typed
     *  email against the session-cached auth user (currentUserOrNull() reads the
     *  in-memory session — no network call). Exact match required; a mismatch returns
     *  false so the caller can block the change and surface an error. */
    fun isEmailVerifiedForUsernameChange(typedEmail: String): Boolean {
        val sessionEmail = supabaseClient.auth.currentUserOrNull()?.email
        return !sessionEmail.isNullOrBlank() && typedEmail.trim() == sessionEmail
    }

    /** Step 2 — push the new handle through the update_username RPC. The server
     *  re-normalizes (trim, strip '@', lowercase) and re-validates 3-20 chars of
     *  [a-z0-9_], then updates profiles.handle under its UNIQUE constraint; a
     *  23505 comes back as a clean "Username already taken". Because every read
     *  joins profiles live, all past/future posts, likes and comments pick the new
     *  handle up automatically — and refreshProfile() flips profileState so the
     *  Compose UI recomposes globally the instant the update lands. */
    fun updateUsername(newUsername: String, onSuccess: () -> Unit) {
        val normalized = newUsername.trim().removePrefix("@").lowercase()
        if (!normalized.matches(Regex("^[a-z0-9_]{3,20}$"))) {
            _errorMessage.value = "Username must be 3-20 characters: lowercase letters, numbers or underscore."
            return
        }
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.postgrest.rpc(
                        "update_username",
                        buildJsonObject { put("p_new_username", normalized) }
                    )
                }
                refreshProfile()
                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = if (e is PostgrestRestException && e.code == "23505") {
                    "Username already taken. Please choose another."
                } else {
                    handleAuthError(e)
                }
            }
        }
    }

    // =====================================================================================
    // 🌟 ACCOUNT DELETION — 3-DAY GRACE PERIOD (REAUTHENTICATION OTP)
    //
    // Identity proof uses Supabase's native reauthentication flow:
    // startAccountDeletion() calls auth.reauthenticate(), which emails the registered
    // address the Reauthentication template's 6-digit code. The user types it into the
    // shared OTP card (DeletionOtpScreen); verifyDeletionOtp() posts the code to the
    // SECURITY DEFINER verify_deletion_otp() RPC, which recomputes GoTrue's
    // hex(sha224(email || otp)) hash, enforces the 1-hour OTP window and — only on a
    // match — stamps profiles.deletion_scheduled_at = now() + 3 days. The UI then routes
    // to PendingDeletionScreen. (GoTrue's /verify endpoint accepts no reauthentication
    // type and PUT /user consumes the nonce only during password updates — forbidding it
    // entirely for SSO users — hence the DB-side verifier in migration …000005.) A daily
    // pg_cron → process-deletions Edge Function permanently erases due accounts (storage
    // media + auth.admin.deleteUser, FK cascades) even if the user logs out meanwhile.
    // cancelAccountDeletion() is the "Revert Changes" path.
    // =====================================================================================
    private val _deletionFlowActive = MutableStateFlow(false)
    val deletionFlowActive: StateFlow<Boolean> = _deletionFlowActive.asStateFlow()

    // Email the reauthentication code was dispatched to (OTP card subtitle).
    private val _deletionOtpEmail = MutableStateFlow("")
    val deletionOtpEmail: StateFlow<String> = _deletionOtpEmail.asStateFlow()

    /** Step 1 — fire the native reauthentication email (6-digit code). */
    fun startAccountDeletion() {
        viewModelScope.launch {
            try {
                clearError()
                val email = supabaseClient.auth.currentUserOrNull()?.email
                    ?: _profileState.value?.email
                if (email.isNullOrBlank()) {
                    _errorMessage.value = "This account has no email address, so deletion cannot be verified."
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    supabaseClient.auth.reauthenticate()
                }
                _deletionOtpEmail.value = email
                _deletionFlowActive.value = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    /** Resend from the OTP card — same native call, no routing side effects. */
    fun resendDeletionOtp() {
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.auth.reauthenticate()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    /** Step 2 — verify the 6-digit code; on success the RPC has already stamped
     *  the 3-day deadline and the flow proceeds to deletion-mode selection.
     *  A wrong or expired code surfaces a clear error and keeps the card up. */
    fun verifyDeletionOtp(rawCode: String, onSuccess: () -> Unit) {
        val cleanCode = rawCode.trim()
        if (cleanCode.isBlank()) {
            _errorMessage.value = "Please enter the verification code."
            return
        }
        viewModelScope.launch {
            try {
                clearError()
                val verified = withContext(Dispatchers.IO) {
                    supabaseClient.postgrest
                        .rpc(
                            "verify_deletion_otp",
                            buildJsonObject { put("p_otp", cleanCode) }
                        )
                        .decodeAs<Boolean>()
                }
                if (verified) {
                    _deletionFlowActive.value = false
                    _deletionOtpEmail.value = ""
                    refreshProfile()
                    onSuccess()
                } else {
                    _errorMessage.value = "Incorrect or expired code. Please try again."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    /** Step 3 — record the chosen deletion mode against the verified schedule.
     *  'soft' closes the account but keeps posts under a "Removed User" alias;
     *  'hard' erases everything. The RPC rejects the call unless
     *  verify_deletion_otp() has already stamped the 3-day deadline. */
    fun chooseDeletionMode(isSoftDelete: Boolean, onScheduled: () -> Unit) {
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.postgrest.rpc(
                        "choose_deletion_mode",
                        buildJsonObject { put("p_is_soft", isSoftDelete) }
                    )
                }
                refreshProfile()
                onScheduled()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    /** Revert Changes — clears deletion_scheduled_at and the chosen mode back to NULL. */
    fun cancelAccountDeletion(onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.postgrest.rpc("cancel_account_deletion")
                }
                refreshProfile()
                onFinished(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAuthError(e)
                onFinished(false)
            }
        }
    }

    /** One-shot consumption of the "route me to the deletion OTP card" trigger. */
    fun consumeDeletionFlow() {
        _deletionFlowActive.value = false
    }

    fun signOut() {
        val previousJob = sessionConflictJob
        val previousChannel = activeSessionChannel
        val signingOutUserId = supabaseClient.auth.currentUserOrNull()?.id

        sessionConflictJob = null
        activeSessionChannel = null
        claimDeviceOnNextSession = false

        hasCompletedColdStartCheck = false
        sessionCreatedInThisRun = false
        RecentVerificationStore.clear()
        _userState.value = null
        _isOnboarding.value = false
        _awaitingOtp.value = false
        _otpEmail.value = ""
        // Deletion UI state resets, but a stamped deletion_scheduled_at stays in
        // the database on purpose — the cron job runs even after logout.
        _deletionFlowActive.value = false
        _deletionOtpEmail.value = ""
        _passwordResetStep.value = null
        _passwordResetResendAvailableAt.value = 0L
        _isPasswordResetBusy.value = false
        _isRecoveryCodeRequested.value = false
        _isRecoverySessionVerified.value = false
        _errorMessage.value = null
        _remoteLogoutEvent.value = false
        emailVerification.cancel()
        pendingEmail = ""
        recoveryEmail = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                previousJob?.cancel()
                try {
                    previousChannel?.unsubscribe()
                } catch (_: Exception) {}

                // Release the device claim while the JWT is still valid. The guard on
                // current_device_id ensures we never clear a claim owned by the device
                // that kicked us (the remote-logout path).
                if (signingOutUserId != null) {
                    try {
                        supabaseClient.from("profiles").update(
                            mapOf("current_device_id" to null)
                        ) {
                            filter {
                                eq("id", signingOutUserId)
                                eq("current_device_id", DeviceIdProvider.deviceId)
                            }
                        }
                    } catch (_: Exception) {}
                }

                supabaseClient.auth.signOut()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun completeOnboarding(gender: String, dob: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                clearError()
                val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return@launch

                withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles").update(
                        {
                            set("gender", gender)
                            set("dob", dob)
                        }
                    ) {
                        filter { eq("id", userId) }
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun updatePassword(newPassword: String, onSuccess: () -> Unit) {
        if (!PasswordPolicy.isValid(newPassword)) {
            _errorMessage.value = PasswordPolicy.ERROR_MESSAGE
            return
        }

        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.auth.updateUser {
                        password = newPassword
                    }

                    val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
                    if (currentUserId != null) {
                        supabaseClient.from("profiles").update(
                            mapOf("password_changed_at" to Instant.now().toString())
                        ) {
                            filter { eq("id", currentUserId) }
                        }
                    }
                }

                _passwordResetStep.value = null
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun updateProfileDetails(firstName: String, lastName: String, gender: String, dob: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                clearError()
                val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return@launch

                withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles").update(
                        {
                            set("first_name", firstName)
                            set("last_name", lastName)
                            set("gender", gender.ifBlank { null })
                            set("dob", dob.ifBlank { null })
                        }
                    ) {
                        filter { eq("id", userId) }
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun saveOnboardingDetails(
        context: Context,
        firstName: String,
        lastName: String,
        handle: String,
        gender: String,
        dob: String,
        realPassword: String,
        avatarRemoteUrl: String? = null,
        avatarLocalUri: Uri? = null,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        // Validate again at the mutation boundary, before uploads or profile writes.
        if (!PasswordPolicy.isValid(realPassword)) {
            onFailure(PasswordPolicy.ERROR_MESSAGE)
            return
        }
        viewModelScope.launch {
            try {
                clearError()
                val currentUser = supabaseClient.auth.currentUserOrNull()
                val userId = currentUser?.id ?: return@launch
                val userEmail = currentUser.email
                Log.d(
                    "AuthDiagnostics",
                    "[onboardingSave] start user=$userId handle=$handle genderBlank=${gender.isBlank()} " +
                        "dobBlank=${dob.isBlank()} emailPresent=${userEmail != null} passwordLength=${realPassword.length}"
                )

                val cleanHandle = handle.trim().removePrefix("@")

                withContext(Dispatchers.IO) {
                    var finalAvatarUrl: String? = avatarRemoteUrl

                    if (avatarLocalUri != null) {
                        try {
                            val bytes = ImageUtils.compressImageToWebp(context.applicationContext, avatarLocalUri)
                            if (bytes.isNotEmpty()) {
                                val fileName = "${userId}/${UUID.randomUUID()}.webp"
                                supabaseClient.storage.from("avatars").upload(fileName, bytes)
                                finalAvatarUrl = supabaseClient.storage.from("avatars").publicUrl(fileName)
                            }
                        } catch (e: Exception) {
                            Log.e("AuthViewModel", "Failed to upload avatar: ${e.localizedMessage}", e)
                        }
                    }

                    // ── STEP 1 (must succeed): persist the profiles row ──────────
                    // This row — NOT user_metadata — is what verifyProfileCompleteness
                    // and signInWithGoogle read back on the next login to decide
                    // onboarding vs feed. It used to run AFTER updateUser{password},
                    // so any password failure silently skipped it and the account
                    // looped back to onboarding forever.
                    supabaseClient.from("profiles").update(
                        {
                            set("first_name", firstName)
                            set("last_name", lastName.ifBlank { null })
                            set("handle", cleanHandle)
                            set("gender", gender.ifBlank { null })
                            set("dob", dob.ifBlank { null })
                            set("email", userEmail)
                            if (!finalAvatarUrl.isNullOrBlank()) {
                                set("avatar_url", finalAvatarUrl)
                            }
                        }
                    ) {
                        filter { eq("id", userId) }
                    }
                    Log.d("AuthDiagnostics", "[onboardingSave] profiles row persisted for user=$userId")

                    // ── STEP 2: set the real password while authenticated ───────
                    // Enables email/username + password sign-in (Issue 2), especially
                    // for Google-registered accounts.
                    //
                    // `same_password` is BENIGN: a returning user re-entered the
                    // password their account already has — the credential goal is
                    // already met, so log the skip and continue instead of failing
                    // onboarding and forcing them to invent a new password.
                    try {
                        supabaseClient.auth.updateUser {
                            password = realPassword
                            data = buildJsonObject {
                                put("first_name", firstName)
                                put("last_name", lastName)
                                put("handle", cleanHandle)
                                put("gender", gender)
                                put("dob", dob)
                            }
                        }
                        Log.d("AuthDiagnostics", "[onboardingSave] password set via auth.updateUser for user=$userId")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val isSamePassword =
                            (e as? RestException)?.error == "same_password" ||
                                e.message?.contains("same_password") == true
                        if (isSamePassword) {
                            Log.i(
                                "AuthDiagnostics",
                                "[onboardingSave] benign skip: entered password is already the account " +
                                    "password for user=$userId (same_password) — continuing"
                            )
                        } else {
                            // Any real failure rethrows -> outer catch -> onFailure flow.
                            throw e
                        }
                    }

                    // ── STEP 3 (best-effort): stamp password_changed_at ─────────
                    try {
                        supabaseClient.from("profiles").update(
                            mapOf("password_changed_at" to Instant.now().toString())
                        ) {
                            filter { eq("id", userId) }
                        }
                    } catch (e: Exception) {
                        Log.w("AuthViewModel", "password_changed_at stamp failed (non-fatal): ${e.localizedMessage}")
                    }
                }

                // Registration finished on this device: claim the single-device session.
                claimDeviceOnNextSession = true
                startDeviceSession(userId)

                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = handleAuthError(e)
                Log.e("AuthDiagnostics", "[onboardingSave] FAILED: ${e.localizedMessage}", e)
                // 🌟 NOT mirrored into _errorMessage: OnboardingFlowScreen presents
                //    this failure inline via the onResult callback and does not
                //    collect the global error flow — mirroring used to leak a stale
                //    error onto the first post-onboarding screen that DID collect it.
                onFailure(message)
            }
        }
    }
}
