package com.example.agora.viewmodel

import android.content.Context
import android.util.Log
import android.util.Patterns
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agora.data.DeviceIdProvider
import com.example.agora.data.supabaseClient
import com.example.agora.model.Profile
import com.example.agora.service.SessionConflictRelay
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.exceptions.RestException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _passwordResetStep = MutableStateFlow<PasswordResetStep?>(null)
    val passwordResetStep: StateFlow<PasswordResetStep?> = _passwordResetStep.asStateFlow()

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

    private val resendTimestamps = mutableListOf<Long>()

    init {
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

    private fun handleAuthError(e: Throwable): String {
        return when (e) {
            is RestException -> {
                when (e.error) {
                    "invalid_credentials", "invalid_grant" -> "Invalid email or password."
                    "user_already_exists" -> "An account with this email already exists."
                    "over_email_send_rate_limit" -> "Too many requests. Please wait a moment before trying again."
                    else -> (e.description ?: "").ifBlank { e.error }
                }
            }
            is io.ktor.client.plugins.HttpRequestTimeoutException,
            is java.net.UnknownHostException,
            is java.net.ConnectException -> "Network error: Please check your internet connection."
            else -> e.localizedMessage ?: "An unexpected error occurred."
        }
    }

    fun resendOtp() {
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

        viewModelScope.launch {
            try {
                clearError()

                supabaseClient.auth.resendEmail(
                    type = OtpType.Email.SIGNUP,
                    email = pendingEmail
                )

                resendTimestamps.add(now)
                _errorMessage.value = "Verification code resent!"
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
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
    }

    fun startPasswordReset() {
        _passwordResetStep.value = PasswordResetStep.EMAIL
    }

    fun requestPasswordResetOtp(email: String) {
        val cleanEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }

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
                _passwordResetStep.value = PasswordResetStep.OTP
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun verifyPasswordResetOtp(otpCode: String) {
        val cleanCode = otpCode.trim()
        if (cleanCode.isBlank()) {
            _errorMessage.value = "Please enter the verification code."
            return
        }

        viewModelScope.launch {
            try {
                clearError()
                withContext(Dispatchers.IO) {
                    supabaseClient.auth.verifyEmailOtp(
                        type = OtpType.Email.RECOVERY,
                        email = recoveryEmail,
                        token = cleanCode
                    )
                }
                _passwordResetStep.value = PasswordResetStep.NEW_PASSWORD
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun submitNewPassword(newPassword: String, onSuccess: () -> Unit) {
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

    fun cancelPasswordReset() {
        _passwordResetStep.value = null
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
                        } else {
                            Log.d("GoogleAuth", "Existing Google user with complete profile. Proceeding to Feed...")
                            _isOnboarding.value = false
                            // Claim this device (latest login wins) and start conflict listeners.
                            claimDeviceOnNextSession = true
                            startDeviceSession(userId)
                        }
                    }

                    onSuccess()
                } else {
                    _errorMessage.value = "Unexpected credential type received"
                }
            } catch (e: GetCredentialException) {
                if (e is NoCredentialException) {
                    _errorMessage.value = "No Google accounts available on device"
                } else {
                    _errorMessage.value = e.localizedMessage ?: "Google Sign-In canceled or failed"
                }
                Log.e("GoogleAuth", "CredentialManager error: ${e.localizedMessage}", e)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = e.localizedMessage ?: "Failed to sign in with Google"
                Log.e("GoogleAuth", "Google sign-in error: ${e.localizedMessage}", e)
            }
        }
    }

    fun startRegistration(emailInput: String) {
        val cleanEmail = emailInput.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }

        viewModelScope.launch {
            try {
                clearError()

                val tempPassword = UUID.randomUUID().toString() + "A1!a"
                val tempHandle = "user_" + UUID.randomUUID().toString().substring(0, 8)

                val response = withContext(Dispatchers.IO) {
                    supabaseClient.auth.signUpWith(Email) {
                        email = cleanEmail
                        password = tempPassword

                        data = buildJsonObject {
                            put("handle", tempHandle)
                            put("first_name", "Pending")
                            put("last_name", "User")
                        }
                    }
                }

                val identities = response?.identities
                if (identities != null && identities.isEmpty()) {
                    _errorMessage.value = "An account with this email already exists. Please log in."
                    return@launch
                }

                pendingEmail = cleanEmail
                _otpEmail.value = cleanEmail
                _awaitingOtp.value = true

            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun verifyOtpCode(otpCode: String) {
        val cleanCode = otpCode.trim()
        if (cleanCode.isBlank()) {
            _errorMessage.value = "Please enter the verification code."
            return
        }

        viewModelScope.launch {
            try {
                clearError()
                _isOnboarding.value = true

                withContext(Dispatchers.IO) {
                    supabaseClient.auth.verifyEmailOtp(
                        type = OtpType.Email.SIGNUP,
                        email = pendingEmail,
                        token = cleanCode
                    )
                }
                _awaitingOtp.value = false
            } catch (e: Exception) {
                _isOnboarding.value = false
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun signOut() {
        val previousJob = sessionConflictJob
        val previousChannel = activeSessionChannel
        val signingOutUserId = supabaseClient.auth.currentUserOrNull()?.id

        sessionConflictJob = null
        activeSessionChannel = null
        claimDeviceOnNextSession = false

        hasCompletedColdStartCheck = false
        _userState.value = null
        _isOnboarding.value = false
        _awaitingOtp.value = false
        _otpEmail.value = 
        _passwordResetStep.value = null
        _errorMessage.value = null
        _remoteLogoutEvent.value = false
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
                _errorMessage.value = message
                onFailure(message)
            }
        }
    }
}
