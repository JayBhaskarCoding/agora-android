package net.agora.app.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import net.agora.app.utils.DateOfBirth
import net.agora.app.utils.SessionContinuity
import net.agora.app.utils.PasswordPolicy
import net.agora.app.utils.PasswordResetRouting
import net.agora.app.utils.OtpCooldown
import net.agora.app.utils.OnboardingRouting
import android.util.Log
import android.util.Patterns
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.agora.app.BuildConfig
import net.agora.app.data.EmailOtpGateway
import net.agora.app.data.EmailVerificationFlow
import net.agora.app.data.DeviceIdProvider
import net.agora.app.data.OnboardingStateStore
import net.agora.app.data.RecentVerificationStore
import net.agora.app.data.supabaseClient
import net.agora.app.model.Profile
import net.agora.app.navigation.AuthCallbackGate
import net.agora.app.service.SessionConflictRelay
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import java.security.MessageDigest
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
import net.agora.app.utils.ErrorMessages
import net.agora.app.utils.ImageUtils
import net.agora.app.utils.handleAppError
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
 * Where the auth shell ([net.agora.app.MainActivity]) must go once a session
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

/**
 * Every OAuth 2.0 client of type "Web application" ends with this, and no
 * Android-type client id does. Used to reject a wrong `serverClientId` *before*
 * the account picker can fail in a way that looks like a user cancellation.
 */
private const val GOOGLE_WEB_CLIENT_SUFFIX = ".apps.googleusercontent.com"

private const val GOOGLE_AUTH_TAG = "GoogleAuth"

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

    /* Two attempts with a short gap: a Postgrest read issued while the restored
     * JWT is still being attached returns EMPTY (RLS filters on auth.uid()) rather
     * than failing, and believing the first empty answer is what put completed
     * accounts back on Create Your Profile. Runs behind the splash. */
    private companion object {
        const val PROFILE_READ_ATTEMPTS = 2
        const val PROFILE_RETRY_DELAY_MS = 300L
    }

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

                            /* ★ A TOKEN REFRESH IS NOT A SIGN-IN.
                             *
                             *   Supabase re-emits `Authenticated` every time the access
                             *   token is renewed — returning to the app from the launcher,
                             *   unlocking the phone, or simply an hour elapsing. The
                             *   session inside `status` is the SAME session, only its
                             *   credentials are fresher (`status.isNew` is false).
                             *
                             *   This collector used to run the cold-start profile check on
                             *   every one of those emits. That check raises
                             *   `isCheckingProfileCompleteness`, and the shell renders a
                             *   spinner instead of MainScreen while it is up — which tears
                             *   down MainScreen's `rememberNavController()` and rebuilds the
                             *   NavHost at `startDestination = feed`. Result: the user comes
                             *   back from the Home screen and is standing on the Feed with
                             *   Account Details / the open post gone from the back stack.
                             *
                             *   So the check is now claimed exactly ONCE per authenticated
                             *   session. [verifyProfileCompleteness] takes the claim
                             *   synchronously, before it launches anything, which also closes
                             *   the window where two back-to-back emits could both get past
                             *   the old in-coroutine flag and check twice.
                             *
                             *   Nothing here resets navigation: the shell only reacts to a
                             *   real identity transition (signed out -> signed in, or back),
                             *   and both of those still land through `navEvent` /
                             *   `isOnboarding` as before. */
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
     * Asks `public.profiles` what state this account is in, and classifies it.
     *
     * ## Why this is a suspend function returning a verdict, not a boolean
     *
     * It replaced `isProfileIncomplete(userId, profile, source): Boolean`, whose
     * very shape forced the bug: a `Boolean` has no way to say **"I could not
     * ask"**, so "no row", "the read failed" and "the row is a placeholder" all
     * collapsed into `true`, and `true` meant both "send to onboarding" **and**
     * "this account is abandoned, delete it".
     *
     * ## The retry
     *
     * A Postgrest read issued while the restored JWT is still being attached
     * returns **zero rows with no error** — RLS filters on `auth.uid()`, which is
     * momentarily null. Believing that first empty answer is how a completed
     * account was re-derived as "new" on a cold start. One silent retry after a
     * short gap covers the attach window; it runs behind the splash spinner, so the
     * user never sees it.
     *
     * A genuinely thrown read (timeout, 401 mid-refresh) is likewise retried once;
     * if it still fails the verdict is [OnboardingRouting.Verdict.UNKNOWN], which
     * can neither onboard nor delete.
     *
     * @return never throws — a failed read is a verdict, not an exception, so no
     *   caller can turn a network blip into a sign-out.
     */
    private suspend fun readProfileVerdict(
        userId: String,
        source: String
    ): OnboardingRouting.Verdict = withContext(Dispatchers.IO) {
        var row: Profile? = null
        var readFailed = false

        for (attempt in 1..PROFILE_READ_ATTEMPTS) {
            try {
                row = supabaseClient.from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()
                readFailed = false
                if (row != null) break
                Log.d(
                    "AuthDiagnostics",
                    "[$source] profiles read attempt $attempt/$PROFILE_READ_ATTEMPTS returned no row " +
                        "for user=$userId (session may still be attaching)"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                readFailed = true
                Log.w(
                    "AuthDiagnostics",
                    "[$source] profiles read attempt $attempt/$PROFILE_READ_ATTEMPTS FAILED " +
                        "for user=$userId: ${e.localizedMessage}"
                )
            }
            if (attempt < PROFILE_READ_ATTEMPTS) delay(PROFILE_RETRY_DELAY_MS)
        }

        /* Evidence from outside the row — the per-user local record and/or the
         * handle onboarding wrote into user_metadata — is read AFTER the query so
         * it reflects the same instant the row was judged at. It can only raise
         * the verdict to COMPLETE (see OnboardingRouting). */
        val completionEvidence = hasCompletionEvidence(userId)
        val verdict = OnboardingRouting.verdict(
            rowPresent = row != null,
            readFailed = readFailed && row == null,
            handle = row?.handle,
            firstName = row?.firstName,
            completionEvidence = completionEvidence
        )
        Log.d(
            "AuthDiagnostics",
            "[$source] user=$userId | row=${row != null} | readFailed=${readFailed && row == null} | " +
                "handle=${row?.handle} | firstName=${row?.firstName} | " +
                "completionEvidence=$completionEvidence | verdict=$verdict"
        )
        verdict
    }

    /**
     * Cold-start gate: exactly one run per authenticated session.
     *
     * Order of belief, strongest first:
     *
     *  1. **This device already recorded a finished onboarding** — go Home, do no
     *     network work, and above all do not delete anything. This is the path that
     *     makes a restart deterministic instead of a coin flip on a query.
     *  2. **The session was created in this run, or verified here recently** — a
     *     placeholder profile means "onboarding has not run yet", never "abandoned".
     *  3. **Otherwise read the row** and classify it. Only a verdict of
     *     [OnboardingRouting.Verdict.NEEDS_ONBOARDING] can reach the cleanup, and
     *     the cleanup is additionally gated by [OnboardingRouting.mayDeleteAccount].
     *
     * Nothing here signs the user out on a failed check. The previous version did,
     * which made a moment of bad connectivity indistinguishable from a stolen
     * session.
     */
    private fun verifyProfileCompleteness(userId: String) {
        if (!SessionContinuity.shouldRunStartupVerification(hasCompletedColdStartCheck)) {
            return
        }
        hasCompletedColdStartCheck = true

        // If user is actively completing onboarding in this session, bypass
        if (_isOnboarding.value) {
            _isCheckingProfileCompleteness.value = false
            return
        }

        /* ★ LOCAL FAST PATH — the fix for "restart sends me back to Create Profile".
         *
         *   A completed onboarding is recorded on this device the moment the
         *   profile row is confirmed persisted. When that record matches this user
         *   id, the destination is Home and no query may overrule it: an empty or
         *   failed read on a cold start is an artefact of the session still being
         *   attached, not evidence that the account is new. */
        if (hasCompletionEvidence(userId)) {
            Log.d(
                "AuthDiagnostics",
                "[coldStart] user=$userId has completion evidence " +
                    "(local=${OnboardingStateStore.isComplete(userId)}, " +
                    "metadata=${metadataProvesOnboarding()}) -> Home (no wipe, no query)"
            )
            // Upgrade evidence into the durable record: a legacy account whose
            // proof came only from metadata gets pinned to this device now, so the
            // next start needs neither a query nor a JWT decode.
            OnboardingStateStore.markComplete(userId)
            _isOnboarding.value = false
            _isCheckingProfileCompleteness.value = false
            sendNav(AuthNavTarget.Home)
            return
        }

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
                val verdict = readProfileVerdict(userId, "coldStart")
                val completionEvidence = hasCompletionEvidence(userId)

                if (OnboardingRouting.mayDeleteAccount(
                        verdict = verdict,
                        completionEvidence = completionEvidence,
                        recentlyVerified = RecentVerificationStore.isRecent(userId),
                        sessionCreatedInThisRun = sessionCreatedInThisRun
                    )
                ) {
                    Log.w(
                        "AuthDiagnostics",
                        "[coldStart] confirmed abandoned placeholder profile — wiping user=$userId"
                    )
                    try {
                        supabaseClient.postgrest.rpc("delete_abandoned_user")
                    } catch (_: Exception) {}
                    signOut()
                    return@launch
                }

                if (verdict == OnboardingRouting.Verdict.COMPLETE) {
                    // Confirmed by the database: remember it so the next start does
                    // not have to ask at all.
                    OnboardingStateStore.markComplete(userId)
                }

                val destination = OnboardingRouting.destination(completionEvidence, verdict)
                _isOnboarding.value = destination == OnboardingRouting.Destination.ONBOARDING
                if (_isOnboarding.value) {
                    // Shield the account across process death: a cold start before
                    // onboarding finishes must resume, never wipe.
                    RecentVerificationStore.mark(userId)
                }
                sendNav(
                    if (_isOnboarding.value) AuthNavTarget.Onboarding else AuthNavTarget.Home
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                /* ★ NEVER SIGN OUT HERE.
                 *   This catch used to call signOut() "to be safe", which turned a
                 *   timeout — or one 401 while the token refreshed — into a logout.
                 *   Fall back to what this device knows: Home if onboarding is
                 *   recorded complete, otherwise resume onboarding. Both preserve
                 *   the account. */
                val completionEvidence = hasCompletionEvidence(userId)
                Log.e(
                    "AuthDiagnostics",
                    "[coldStart] profile check FAILED for user=$userId — " +
                        "keeping the session, completionEvidence=$completionEvidence: ${e.localizedMessage}",
                    e
                )
                _isOnboarding.value = !completionEvidence
                sendNav(
                    if (completionEvidence) AuthNavTarget.Home else AuthNavTarget.Onboarding
                )
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
     * This NEVER wipes the account: the session was just established (or the email
     * was verified on this device moments ago), so an incomplete profile means
     * "onboarding has not run yet" — not "abandoned". A read that fails, or a
     * profile this device already recorded as complete, resolves to Home.
     */
    private fun resolveAfterAuthentication(userId: String, source: String) {
        viewModelScope.launch {
            try {
                _isCheckingProfileCompleteness.value = true
                hasCompletedColdStartCheck = true

                /* The local record outranks the query here for the same reason it
                 * does at cold start: `sessionStatus` re-emits `Authenticated` on
                 * token refresh and after `updateUser`, and re-deriving "is this a
                 * new user?" from the network on every one of those is how a
                 * finished account ends up back on Create Your Profile. */
                if (hasCompletionEvidence(userId)) {
                    Log.d(
                        "AuthDiagnostics",
                        "[$source] user=$userId has completion evidence " +
                            "(local=${OnboardingStateStore.isComplete(userId)}, " +
                            "metadata=${metadataProvesOnboarding()}) -> Home"
                    )
                    OnboardingStateStore.markComplete(userId)
                    _isOnboarding.value = false
                    _isCheckingProfileCompleteness.value = false
                    sendNav(AuthNavTarget.Home)
                    return@launch
                }

                val verdict = readProfileVerdict(userId, source)
                val completionEvidence = hasCompletionEvidence(userId)

                if (verdict == OnboardingRouting.Verdict.COMPLETE) {
                    OnboardingStateStore.markComplete(userId)
                }

                val destination = OnboardingRouting.destination(completionEvidence, verdict)
                _isOnboarding.value = destination == OnboardingRouting.Destination.ONBOARDING
                _isCheckingProfileCompleteness.value = false

                if (_isOnboarding.value) {
                    // Shield the account across process death.
                    RecentVerificationStore.mark(userId)
                }
                sendNav(
                    if (_isOnboarding.value) AuthNavTarget.Onboarding else AuthNavTarget.Home
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val completionEvidence = hasCompletionEvidence(userId)
                Log.e(
                    "AuthDiagnostics",
                    "[$source] profile resolution FAILED for user=$userId — " +
                        "completionEvidence=$completionEvidence, keeping the session: ${e.localizedMessage}",
                    e
                )
                _isOnboarding.value = !completionEvidence
                _isCheckingProfileCompleteness.value = false
                if (_isOnboarding.value) RecentVerificationStore.mark(userId)
                sendNav(
                    if (completionEvidence) AuthNavTarget.Home else AuthNavTarget.Onboarding
                )
            }
        }
    }

    /**
     * Everything the device already knows about whether onboarding finished for
     * [userId] — without asking the database.
     *
     * Two independent sources, both of which outrank a failed read:
     *
     *  1. [OnboardingStateStore] — written the moment a profile write is *verified*
     *     (and on every confirmed-complete verdict), cleared on sign-out.
     *  2. `user_metadata.handle` / `username` — written by onboarding itself, so it
     *     also covers accounts that onboarded on a build that predates the store.
     *
     * Kept as one predicate so no call site can accidentally honour only one of
     * them: the deletion gate, the cold-start fast path and the post-auth resolver
     * must all see the same evidence.
     */
    private fun hasCompletionEvidence(userId: String): Boolean =
        OnboardingStateStore.isComplete(userId) || metadataProvesOnboarding()

    /**
     * Reads the "onboarding already ran" flag out of the live session's metadata.
     *
     * `currentUserOrNull()` is populated by definition here — every caller is
     * inside an `Authenticated` branch or a post-sign-in continuation, which is
     * also why the Google path may reuse it instead of threading its own copy
     * through.
     */
    private fun metadataProvesOnboarding(): Boolean {
        val metadata = supabaseClient.auth.currentUserOrNull()?.userMetadata ?: return false
        return OnboardingRouting.metadataProvesOnboarding(
            handle = metadata.stringOrNull("handle"),
            username = metadata.stringOrNull("username")
        )
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
        /* ★ PERSIST THE COMPLETION, don't just flip a boolean.
         *
         *   This is the flag the audit asked about, and until now it did not exist:
         *   "onboarded" lived only in this ViewModel, so every restart re-derived
         *   it from the network. It is written here as well as in
         *   [saveOnboardingDetails] because either path may be the last one the user
         *   takes before backgrounding the app. */
        supabaseClient.auth.currentUserOrNull()?.id?.let { OnboardingStateStore.markComplete(it) }

        hasCompletedColdStartCheck = true
        _isCheckingProfileCompleteness.value = false
        _isOnboarding.value = false
        // Profile is complete — the account no longer needs the cold-start shield.
        RecentVerificationStore.clear()
        // ★ Explicit route to the feed now that onboarding is done.
        sendNav(AuthNavTarget.Home)
    }

    /**
     * Entry point for *Change Password* (authenticated).
     *
     * Runs the SAME three steps as recovery — email, emailed code, new password
     * — because a live session proves *an account is open on this device*, not
     * that the person holding it is the owner. Steps 1 and 2 re-establish that
     * with something the owner physically possesses.
     *
     * Without a session there is nothing to change, so it degrades to the
     * signed-out recovery flow rather than starting a CHANGE that can never
     * pass its own email check.
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
        recoveryEmail = ""
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

    /** Back from the code step to the email step — valid in both modes. */
    fun editRecoveryEmail() {
        if (_isPasswordResetBusy.value) return
        _isRecoveryCodeRequested.value = false
        _isRecoverySessionVerified.value = false
        _passwordResetResendAvailableAt.value = 0L
        // Back to step 1 through the same policy both entry points use, so this
        // path can never drift to a different starting step.
        _passwordResetStep.value = PasswordResetRouting.startStep(_passwordResetMode.value)
        clearError()
    }

    fun requestPasswordResetOtp(email: String) {
        val cleanEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }
        if (_isPasswordResetBusy.value) return
        // A resend must respect the same 60-second window as the shared OTP card;
        // the very first send (EMAIL step) is never gated by it.
        if (_passwordResetStep.value == PasswordResetStep.OTP &&
            SystemClock.elapsedRealtime() < _passwordResetResendAvailableAt.value
        ) {
            return
        }

        /* ★ OWNERSHIP CHECK — runs BEFORE any mail is sent, in BOTH modes.
         *
         *   CHANGE  : the typed address must be the account this device is
         *             signed in as. This is the whole point of step 1 on a
         *             device somebody else may be holding: issuing a code to a
         *             *different* mailbox would let the holder of the phone
         *             drive a takeover of that other account.
         *   RECOVERY: a session may still exist (an interrupted flow, a
         *             reinstated process). If so, the same rule applies; a
         *             signed-out device skips it and falls through to the
         *             profiles lookup below.
         *
         *   Case-insensitive: mail domains normalise case, and "Jay@x.com" is
         *   the same mailbox as "jay@x.com". */
        val mode = _passwordResetMode.value
        val activeEmail = supabaseClient.auth.currentUserOrNull()?.email
        if (mode == PasswordResetMode.CHANGE && activeEmail.isNullOrBlank()) {
            _errorMessage.value =
                "We could not read the email on this account. Please sign in again."
            return
        }
        if (!activeEmail.isNullOrBlank() && !cleanEmail.equals(activeEmail, ignoreCase = true)) {
            _errorMessage.value = if (mode == PasswordResetMode.CHANGE) {
                "That email does not match the account you are signed in as."
            } else {
                "This email does not match your active account."
            }
            return
        }

        _isPasswordResetBusy.value = true
        viewModelScope.launch {
            try {
                clearError()

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
                // Canonical address when a session tells us what it is: Supabase
                // mints and verifies the code against the account's own email,
                // and the ownership check above already proved these are the same
                // mailbox (case-insensitively). Signed-out recovery keeps exactly
                // what the user typed.
                recoveryEmail = activeEmail ?: cleanEmail
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
    /**
     * [webClientId] MUST be the OAuth 2.0 **Web application** client id of the
     * same Google Cloud project as `app/google-services.json`, and that project
     * MUST also hold an **Android** OAuth client for `net.agora.app` signed with
     * the certificate of the *installed* APK.
     *
     * When any of those three is wrong, Play Services closes the account sheet
     * and Credential Manager surfaces
     * `GetCredentialCancellationException: activity is cancelled by the user`
     * (or `[28444] Developer console is not set up correctly`). Google never
     * tells the app which one it was — hence [logGoogleSignInDiagnostics], which
     * prints every input Google needs on one logcat line.
     */
    fun signInWithGoogle(
        context: Context,
        webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                clearError()

                /* ✦ FAIL FAST, FAIL HONESTLY.
                 * An Android-type client id (or a blank one) cannot mint a
                 * Google ID token. Detecting it here turns a silent
                 * "user cancelled" into a message that names the real problem. */
                if (!webClientId.endsWith(GOOGLE_WEB_CLIENT_SUFFIX)) {
                    _errorMessage.value = ErrorMessages.GOOGLE_CONFIG_ERROR
                    Log.e(
                        GOOGLE_AUTH_TAG,
                        "GOOGLE_WEB_CLIENT_ID is not a Web OAuth client id: '$webClientId'. " +
                            "It must end with $GOOGLE_WEB_CLIENT_SUFFIX — set " +
                            "GOOGLE_WEB_CLIENT_ID in local.properties."
                    )
                    return@launch
                }

                logGoogleSignInDiagnostics(context, webClientId)

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

                    /* 🌟 CRITICAL: authenticate with Supabase Auth using the
                     *   Google ID token via the IDToken provider.
                     *
                     *   `provider = Google` is what makes GoTrue validate the
                     *   JWT against Google's issuer/audience; no OAuth redirect
                     *   and no scopes are involved because the token was minted
                     *   natively by Play Services.
                     *
                     *   ✦ The session flags are set only AFTER the exchange
                     *   succeeds. They used to be set before it, so a failed
                     *   exchange still marked this run as "session created
                     *   here", which defeats the abandoned-account cleanup. */
                    withContext(Dispatchers.IO) {
                        supabaseClient.auth.signInWith(IDToken) {
                            idToken = token
                            provider = Google
                        }
                    }

                    claimDeviceOnNextSession = true
                    hasCompletedColdStartCheck = true
                    sessionCreatedInThisRun = true

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

                        /* Same three-way policy as every other entry point. A
                         * Google sign-in on a device that already recorded a
                         * completed onboarding cannot be sent back through the
                         * wizard, and a failed read stays UNKNOWN (Home), never
                         * "new user". */
                        val googleVerdict = OnboardingRouting.verdict(
                            rowPresent = profile != null,
                            readFailed = profileQueryFailed,
                            handle = profile?.handle,
                            firstName = profile?.firstName,
                            completionEvidence = hasCompletionEvidence(userId)
                        )

                        if (profileQueryFailed || googleVerdict == OnboardingRouting.Verdict.UNKNOWN) {
                            // Transient failure (network/RLS): do NOT force the user back
                            // through onboarding — treat as an existing account. The cold-start
                            // verifier and the next login will re-check completeness.
                            Log.w("AuthDiagnostics", "[googleSignIn] treating user=$userId as existing (query failed, not incomplete)")
                            _isOnboarding.value = false
                            claimDeviceOnNextSession = true
                            startDeviceSession(userId)
                            sendNav(AuthNavTarget.Home)
                        } else if (googleVerdict == OnboardingRouting.Verdict.NEEDS_ONBOARDING) {
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
                            OnboardingStateStore.markComplete(userId)
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
                /* ✦ Credential Manager collapses several VERY different failures
                 *   into the same exception family. `GetCredentialCancellationException`
                 *   is raised both when the user really backs out AND when Google
                 *   silently rejects the app (`[16] Account reauth failed`,
                 *   `[28444] Developer console is not set up correctly`,
                 *   `not registered to use OAuth`). Reporting all of them as
                 *   "canceled" is precisely how this class of bug survives
                 *   triage — so classify on the payload, not the exception type. */
                val detail = credentialFailureDetail(e)
                when {
                    e is NoCredentialException ->
                        _errorMessage.value =
                            "No Google account is available on this device. Add one in Settings and try again."

                    isGoogleOAuthConfigFailure(detail) ->
                        _errorMessage.value = ErrorMessages.GOOGLE_CONFIG_ERROR

                    e is GetCredentialCancellationException ->
                        // Genuine user cancel: the picker was dismissed.
                        _errorMessage.value = "Google Sign-In was canceled."

                    e is GetCredentialProviderConfigurationException ||
                        e is GetCredentialUnsupportedException ->
                        _errorMessage.value = ErrorMessages.GOOGLE_CONFIG_ERROR

                    e is GetCredentialInterruptedException ->
                        _errorMessage.value = "Google Sign-In was interrupted. Please try again."

                    e is GetCredentialCustomException ->
                        _errorMessage.value = ErrorMessages.GOOGLE_CONFIG_ERROR

                    else -> _errorMessage.value = handleAppError(e)
                }
                Log.e(GOOGLE_AUTH_TAG, "CredentialManager failure [$detail]", e)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = handleAppError(e)
                Log.e(GOOGLE_AUTH_TAG, "Google sign-in error: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Flattens everything Credential Manager knows about a failure into one
     * string: exception class + `type` + `errorMessage`. `errorMessage` is the
     * only place the numeric Google status (`[28444]`, `[16]`, `10:`) survives.
     */
    private fun credentialFailureDetail(e: GetCredentialException): String = buildString {
        append(e.javaClass.simpleName)
        append(" type=").append(e.type)
        // GetCredentialException's constructor forwards errorMessage to
        // Exception(message), so `message` already carries the Google status
        // ([28444], [16], …) — no reflection or version-specific accessor needed.
        e.message?.takeIf { it.isNotBlank() }?.let { append(" message=").append(it) }
    }

    /**
     * True when a credential failure is really a Google Cloud / OAuth-client
     * misconfiguration rather than a user action.
     *
     * These arrive in three disguises:
     *  - `[28444] Developer console is not set up correctly` (DEVELOPER_ERROR 10)
     *  - `[16] Account reauth failed` — what Credential Manager says when the
     *    signing certificate matches **no registered Android OAuth client**
     *  - `not registered to use OAuth2.0 … confirm the package name and SHA-1`
     */
    private fun isGoogleOAuthConfigFailure(detail: String): Boolean {
        val text = detail.lowercase()
        return text.contains("28444") ||
            text.contains("developer console is not set up") ||
            text.contains("account reauth failed") ||
            text.contains("developer_error") ||
            text.contains("not registered to use oauth")
    }

    /**
     * One logcat line containing every input Google validates.
     *
     * Google matches the calling app on **package name + signing-certificate
     * SHA-1** against an Android OAuth client *and* requires the
     * `serverClientId` to be a Web client of the same project. Printing all of
     * them — plus the project number embedded in the client id — turns a
     * silent cancellation into a one-look diagnosis:
     *
     *     adb logcat -s GoogleAuth
     */
    @Suppress("DEPRECATION")
    private fun logGoogleSignInDiagnostics(context: Context, webClientId: String) {
        val sha1 = runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
            certs?.joinToString(",") { sig ->
                MessageDigest.getInstance("SHA-1")
                    .digest(sig.toByteArray())
                    .joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
            }
        }.getOrNull().orEmpty()

        val firebaseProject = runCatching { FirebaseApp.getInstance().options.gcmSenderId }.getOrNull()
        val clientProject = webClientId.substringBefore('-')

        Log.i(
            GOOGLE_AUTH_TAG,
            "preflight package=${context.packageName} sha1=${sha1.ifBlank { "unavailable" }} " +
                "serverClientId=$webClientId (project $clientProject) " +
                "firebaseProject=${firebaseProject ?: "unavailable"}"
        )

        if (firebaseProject != null && clientProject != firebaseProject) {
            Log.e(
                GOOGLE_AUTH_TAG,
                "MISCONFIGURATION: GOOGLE_WEB_CLIENT_ID belongs to Google Cloud project " +
                    "$clientProject but this build's google-services.json is project " +
                    "$firebaseProject. The Web OAuth client and the Android OAuth client must " +
                    "live in the SAME project as the app."
            )
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
        // The completion record is per-identity: a different account signing in on
        // this device must not inherit it.
        OnboardingStateStore.clear()
        _userState.value = null
        _isOnboarding.value = false
        _awaitingOtp.value = false
        _otpEmail.value = ""
        // Deletion UI state resets, but a stamped deletion_scheduled_at stays in
        // the database on purpose — the cron job runs even after logout.
        _deletionFlowActive.value = false
        _deletionOtpEmail.value = ""
        _passwordResetStep.value = null
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
        val dobError = DateOfBirth.validationError(dob)
        if (dobError != null) {
            _errorMessage.value = dobError
            return
        }
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
        if (newPassword.length < 6) {
            _errorMessage.value = "Password must be at least 6 characters."
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
        // 🌟 Mirror of the inline form validation: a manually entered or stale
        //    DOB outside [today − 120 years, today] can never reach the database.
        val dobError = DateOfBirth.validationError(dob)
        if (dobError != null) {
            _errorMessage.value = dobError
            return
        }
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
        val dobError = DateOfBirth.validationError(dob)
        if (dobError != null) {
            onFailure(dobError)
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
                    // This row — NOT user_metadata — is what readProfileVerdict
                    // and signInWithGoogle read back on the next login to decide
                    // onboarding vs feed. It used to run AFTER updateUser{password},
                    // so any password failure silently skipped it and the account
                    // looped back to onboarding forever.
                    //
                    // ★ AND IT IS VERIFIED, not just issued.
                    //   `update` is a no-op that reports SUCCESS when its filter
                    //   matches nothing, so a missing profiles row used to look
                    //   exactly like a successful save: the wizard advanced, the
                    //   in-memory flag said "onboarded", Home opened — and the next
                    //   start re-read the untouched placeholder row and sent the
                    //   user back to Create Your Profile. See persistProfileRow.
                    persistProfileRow(
                        userId = userId,
                        firstName = firstName,
                        lastName = lastName.ifBlank { null },
                        handle = cleanHandle,
                        gender = gender.ifBlank { null },
                        dob = dob.ifBlank { null },
                        email = userEmail,
                        avatarUrl = finalAvatarUrl
                    )
                    Log.d("AuthDiagnostics", "[onboardingSave] profiles row persisted and verified for user=$userId")

                    // ── STEP 2: set the real password while authenticated ───────
                    // Enables email/username + password sign-in (Issue 2), especially
                    // for Google-registered accounts.
                    //
                    // `same_password` is BENIGN: a returning user re-entered the
                    // password their account already has — the credential goal is
                    // already met, so log the skip and continue instead of failing
                    // onboarding and forcing them to invent a new password.
                    try {
                        /* ★ METADATA MUST END UP SHAPED LIKE A GOOGLE USER'S.
                         *
                         *   Google identities carry `full_name`/`name` (plus
                         *   `picture`, `iss`, `sub`, …) in `user_metadata`; email
                         *   OTP identities carried only `first_name`/`last_name`/
                         *   `handle`. Anything that reads `session.user.userMetadata`
                         *   therefore saw two different shapes depending on how the
                         *   account was created. Both are written here, and the keys
                         *   already present are copied through first so this can
                         *   never drop `picture` or anything else the provider set. */
                        val existingMetadata = supabaseClient.auth.currentUserOrNull()?.userMetadata
                        val fullName = listOf(firstName, lastName)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")

                        supabaseClient.auth.updateUser {
                            password = realPassword
                            data = buildJsonObject {
                                // Preserve whatever is already there (Google's
                                // picture/iss/sub, an older first_name, …).
                                existingMetadata?.forEach { (key, value) ->
                                    put(key, value)
                                }
                                put("first_name", firstName)
                                put("last_name", lastName)
                                put("handle", cleanHandle)
                                put("username", cleanHandle)
                                put("full_name", fullName)
                                put("name", fullName)
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

                    // ── STEP 3: intentionally NOT stamping password_changed_at ──
                    // Setting the initial onboarding credential is not a
                    // "password change": new accounts must surface "Never" in
                    // Account Details until the user explicitly completes the
                    // Change Password or Reset Password flow.
                }

                /* ★ RECORD IT LOCALLY THE MOMENT THE ROW IS CONFIRMED.
                 *
                 *   Not in [finishOnboarding] only: the user can kill the app on
                 *   the theme step, and the profile is already complete at this
                 *   point. Recording here is what makes the *next* start
                 *   deterministic — it never has to re-derive "did onboarding
                 *   finish?" from a network read. Cleared on sign-out. */
                OnboardingStateStore.markComplete(userId)

                // Registration finished on this device: claim the single-device session.
                claimDeviceOnNextSession = true
                startDeviceSession(userId)

                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = if (e is ProfileWriteFailed) e.userMessage else handleAuthError(e)
                Log.e("AuthDiagnostics", "[onboardingSave] FAILED: ${e.localizedMessage}", e)
                // 🌟 NOT mirrored into _errorMessage: OnboardingFlowScreen presents
                //    this failure inline via the onResult callback and does not
                //    collect the global error flow — mirroring used to leak a stale
                //    error onto the first post-onboarding screen that DID collect it.
                onFailure(message)
            }
        }
    }

    // =====================================================================================
    // ONBOARDING PERSISTENCE
    // =====================================================================================

    /** Thrown when the profiles row could not be confirmed after writing it. */
    private class ProfileWriteFailed(val userMessage: String) : Exception(userMessage)

    /**
     * Writes the onboarding profile row and **proves it landed**.
     *
     * ## The bug this replaces
     *
     * The old code was a single `.update()` with no confirmation. Postgrest treats
     * an `UPDATE` whose filter matches no rows as a *successful* request, so when
     * the row was missing — a trigger that did not run, an account created before
     * the trigger existed, a row removed by the abandonment cleanup — the write
     * did nothing and onboarding still reported success. The user reached Home
     * (because the shell trusts the in-memory flag), stayed there for the session,
     * and was sent back to "Create Your Profile" on the next start, when the
     * decision was re-derived from that same untouched row.
     *
     * ## The sequence
     *
     * 1. `UPDATE` — the path every existing account takes; needs only the UPDATE
     *    policy that already exists.
     * 2. Read the row back and compare the handle. A verification read that itself
     *    fails is treated as "probably fine" rather than blocking the user: the
     *    local completion record now protects them either way.
     * 3. If the row is genuinely missing, `INSERT` it (allowed by
     *    `20990101000011_profiles_self_insert.sql`).
     * 4. Verify again; a silent no-op must never be reported as success.
     *
     * @throws ProfileWriteFailed when the write could not be confirmed.
     */
    private suspend fun persistProfileRow(
        userId: String,
        firstName: String,
        lastName: String?,
        handle: String,
        gender: String?,
        dob: String?,
        email: String?,
        avatarUrl: String?
    ) {
        val avatar = avatarUrl?.takeIf { it.isNotBlank() }

        withContext(Dispatchers.IO) {
            // ── 1. UPDATE ───────────────────────────────────────────────────────
            supabaseClient.from("profiles").update(
                {
                    set("first_name", firstName)
                    set("last_name", lastName)
                    set("handle", handle)
                    set("gender", gender)
                    set("dob", dob)
                    set("email", email)
                    // 🌟 A brand-new account has NEVER had an explicit password
                    //    change/reset. Onboarding sets the initial credential, so
                    //    clear any column default/trigger stamp here; only Change
                    //    Password / Reset Password may write this timestamp.
                    //    The explicit type is required: postgrest-kt exposes
                    //    set(String, String?/Int?/Long?/…/T?) overloads, so a bare
                    //    `null` literal is ambiguous.
                    set("password_changed_at", null as String?)
                    if (avatar != null) {
                        set("avatar_url", avatar)
                    }
                }
            ) {
                filter { eq("id", userId) }
            }

            // ── 2. VERIFY ───────────────────────────────────────────────────────
            when (rowHasHandle(userId, handle)) {
                true -> {
                    Log.d("AuthDiagnostics", "[onboardingSave] UPDATE verified for user=$userId")
                    return@withContext
                }
                // Could not check. Do not fail the user over a verification read —
                // the local completion record covers the next start.
                null -> {
                    Log.w(
                        "AuthDiagnostics",
                        "[onboardingSave] UPDATE issued but could not be verified for user=$userId — continuing"
                    )
                    return@withContext
                }
                false -> Unit // fall through: the row is missing, create it
            }

            // ── 3. INSERT (row was missing; the UPDATE matched nothing) ─────────
            Log.w(
                "AuthDiagnostics",
                "[onboardingSave] no profiles row for user=$userId after UPDATE — inserting"
            )
            val values = buildMap<String, String> {
                put("id", userId)
                put("first_name", firstName)
                put("handle", handle)
                lastName?.let { put("last_name", it) }
                gender?.let { put("gender", it) }
                dob?.let { put("dob", it) }
                email?.let { put("email", it) }
                avatar?.let { put("avatar_url", it) }
            }
            try {
                supabaseClient.from("profiles").insert(values)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(
                    "AuthDiagnostics",
                    "[onboardingSave] INSERT failed for user=$userId: ${e.localizedMessage}",
                    e
                )
                throw ProfileWriteFailed(
                    "We couldn't save your profile. Please check your connection and try again."
                )
            }

            // ── 4. VERIFY AGAIN ─────────────────────────────────────────────────
            when (rowHasHandle(userId, handle)) {
                false -> throw ProfileWriteFailed(
                    "We couldn't save your profile. Please check your connection and try again."
                )
                else -> Log.d("AuthDiagnostics", "[onboardingSave] INSERT verified for user=$userId")
            }
        }
    }

    /**
     * True when the stored handle matches [expected]; null when the check itself
     * could not run.
     *
     * The tri-state matters: `false` means "the row is missing or still a
     * placeholder" and must trigger the insert path, while null means "I don't
     * know" and must not fail a user whose write probably succeeded.
     */
    private suspend fun rowHasHandle(userId: String, expected: String): Boolean? =
        withContext(Dispatchers.IO) {
            try {
                val row = supabaseClient.from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()
                row != null && row.handle?.trim() == expected
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(
                    "AuthDiagnostics",
                    "[onboardingSave] verification read failed for user=$userId: ${e.localizedMessage}"
                )
                null
            }
        }

}
