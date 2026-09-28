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
import com.example.agora.data.supabaseClient
import com.example.agora.model.Profile
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
import io.github.jan.supabase.realtime.broadcast.BroadcastPayload
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

enum class PasswordResetStep { EMAIL, OTP, NEW_PASSWORD }

class AuthViewModel : ViewModel() {

    private var activeSessionChannel: RealtimeChannel? = null
    private var sessionConflictJob: Job? = null

    val sessionStatus = supabaseClient.auth.sessionStatus

    private val _remoteLogoutEvent = MutableStateFlow(false)
    val remoteLogoutEvent: StateFlow<Boolean> = _remoteLogoutEvent.asStateFlow()

    private var localSessionId: String = ""

    private val _userState = MutableStateFlow<UserInfo?>(null)
    val userState: StateFlow<UserInfo?> = _userState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _awaitingOtp = MutableStateFlow(false)
    val awaitingOtp: StateFlow<Boolean> = _awaitingOtp.asStateFlow()

    private var pendingEmail: String = ""

    private val _passwordResetStep = MutableStateFlow<PasswordResetStep?>(null)
    val passwordResetStep: StateFlow<PasswordResetStep?> = _passwordResetStep.asStateFlow()

    private var recoveryEmail: String = ""

    private val _isOnboarding = MutableStateFlow(false)
    val isOnboarding: StateFlow<Boolean> = _isOnboarding.asStateFlow()

    private val _isCheckingProfileCompleteness = MutableStateFlow(true)
    val isCheckingProfileCompleteness: StateFlow<Boolean> = _isCheckingProfileCompleteness.asStateFlow()

    private var hasCompletedColdStartCheck = false

    private val resendTimestamps = mutableListOf<Long>()

    init {
        viewModelScope.launch {
            val currentUser = supabaseClient.auth.currentUserOrNull()
            _userState.value = currentUser

            currentUser?.id?.let { userId ->
                listenForSessionConflicts(userId)
            }

            supabaseClient.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        _userState.value = user
                        user?.id?.let { userId ->
                            if (activeSessionChannel == null || localSessionId.isBlank()) {
                                listenForSessionConflicts(userId)
                            }
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

                val profile = supabaseClient.from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()

                val isIncomplete = profile == null ||
                        profile.firstName == "Pending" ||
                        profile.lastName == "User" ||
                        profile.handle.startsWith("user_") ||
                        profile.gender.isNullOrBlank() ||
                        profile.dob.isNullOrBlank()

                if (isIncomplete) {
                    // ABANDONMENT WIPE: Cold start detected an abandoned session from a previous run
                    try {
                        supabaseClient.postgrest.rpc("delete_abandoned_user")
                    } catch (_: Exception) {}
                    signOut()
                } else {
                    _isOnboarding.value = false
                }
            } catch (_: Exception) {
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

    private suspend fun broadcastSessionChange(userId: String, newSessionId: String) {
        val enableRealtime = false
        if (!enableRealtime) return

        try {
            val channel = supabaseClient.channel("session_conflict_$userId")
            channel.subscribe()
            channel.broadcast(
                event = "session_changed",
                payload = BroadcastPayload.Json(buildJsonObject { put("session_id", newSessionId) })
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun listenForSessionConflicts(userId: String, explicitSessionId: String? = null) {
        val enableRealtime = false
        if (!enableRealtime) return

        val previousJob = sessionConflictJob
        val previousChannel = activeSessionChannel

        sessionConflictJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                previousJob?.cancel()
                try {
                    previousChannel?.unsubscribe()
                } catch (_: Exception) {}

                activeSessionChannel = null

                if (!explicitSessionId.isNullOrBlank()) {
                    localSessionId = explicitSessionId
                } else if (localSessionId.isBlank()) {
                    val profile = supabaseClient.from("profiles")
                        .select {
                            filter { eq("id", userId) }
                        }.decodeSingleOrNull<Profile>()

                    val serverSessionId = profile?.activeSessionId

                    if (serverSessionId.isNullOrBlank()) {
                        localSessionId = UUID.randomUUID().toString()
                        supabaseClient.from("profiles").update(
                            mapOf("active_session_id" to localSessionId)
                        ) {
                            filter { eq("id", userId) }
                        }
                    } else {
                        localSessionId = serverSessionId
                    }
                }

                try {
                    supabaseClient.realtime.connect()
                } catch (_: Exception) {}

                val channel = supabaseClient.channel("session_conflict_$userId")
                activeSessionChannel = channel

                val broadcastFlow = channel.broadcastFlow("session_changed")
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "profiles"
                    filter("id", FilterOperator.EQ, userId)
                }

                channel.subscribe()

                // Polling Fallback (runs every 3 seconds)
                val pollingJob = launch {
                    while (isActive) {
                        delay(3000)
                        try {
                            val serverProfile = supabaseClient.from("profiles")
                                .select { filter { eq("id", userId) } }
                                .decodeSingleOrNull<Profile>()

                            val currentServerId = serverProfile?.activeSessionId
                            if (!currentServerId.isNullOrEmpty() && localSessionId.isNotEmpty() && currentServerId != localSessionId) {
                                triggerAutomaticLogout()
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Listen to Realtime Broadcast Messages
                launch {
                    broadcastFlow.collect { broadcast ->
                        val payloadJson = (broadcast.payload as? BroadcastPayload.Json)?.value
                        val jsonObject = payloadJson as? JsonObject
                        val newSessionId = jsonObject?.get("session_id")?.jsonPrimitive?.content
                        if (!newSessionId.isNullOrEmpty() && localSessionId.isNotEmpty() && newSessionId != localSessionId) {
                            pollingJob.cancel()
                            triggerAutomaticLogout()
                        }
                    }
                }

                // Listen to Postgres Changes
                changeFlow.collect { action ->
                    val newSessionId = action.record["active_session_id"]?.jsonPrimitive?.content
                    if (!newSessionId.isNullOrEmpty() && localSessionId.isNotEmpty() && newSessionId != localSessionId) {
                        pollingJob.cancel()
                        triggerAutomaticLogout()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    private suspend fun triggerAutomaticLogout() {
        withContext(Dispatchers.Main) {
            _errorMessage.value = "Your session has ended because a new device logged into this account."
            _remoteLogoutEvent.value = true
            signOut()
        }
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
                val existingProfiles = supabaseClient.from("profiles")
                    .select { filter { eq("email", cleanEmail) } }
                    .decodeList<Profile>()

                if (existingProfiles.isEmpty()) {
                    _errorMessage.value = "No account found with this email address."
                    return@launch
                }

                supabaseClient.auth.resetPasswordForEmail(cleanEmail)
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
                supabaseClient.auth.verifyEmailOtp(
                    type = OtpType.Email.RECOVERY,
                    email = recoveryEmail,
                    token = cleanCode
                )
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

            if (!Patterns.EMAIL_ADDRESS.matcher(loginEmail).matches()) {
                val handleToSearch = loginEmail.removePrefix("@")
                try {
                    val response = supabaseClient.from("profiles")
                        .select { filter { eq("handle", handleToSearch) } }
                        .decodeSingleOrNull<JsonObject>()

                    val foundEmail = response?.get("email")?.jsonPrimitive?.content
                    if (foundEmail != null) {
                        loginEmail = foundEmail
                    } else {
                        _errorMessage.value = "Username not found."
                        return@launch
                    }
                } catch (e: Exception) {
                    _errorMessage.value = handleAuthError(e)
                    return@launch
                }
            }

            try {
                supabaseClient.auth.signInWith(Email) {
                    email = loginEmail
                    password = passwordInput
                }
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
                return@launch
            }

            try {
                val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return@launch
                val newSessionId = UUID.randomUUID().toString()

                supabaseClient.from("profiles").update(
                    mapOf("active_session_id" to newSessionId)
                ) {
                    filter { eq("id", userId) }
                }

                broadcastSessionChange(userId, newSessionId)
                listenForSessionConflicts(userId, explicitSessionId = newSessionId)
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

                    Log.d("GoogleAuth", "Retrieved Google ID Token: $token")

                    // 🌟 CRITICAL: Authenticate with Supabase Auth using Google ID Token via IDToken provider
                    withContext(Dispatchers.IO) {
                        supabaseClient.auth.signInWith(IDToken) {
                            idToken = token
                            provider = Google
                        }
                    }

                    val userId = supabaseClient.auth.currentUserOrNull()?.id
                    if (userId != null) {
                        // 🌟 Differentiating Login vs Registration: Query profiles table
                        val profile = withContext(Dispatchers.IO) {
                            try {
                                supabaseClient.from("profiles")
                                    .select { filter { eq("id", userId) } }
                                    .decodeSingleOrNull<Profile>()
                            } catch (_: Exception) {
                                null
                            }
                        }

                        val isIncomplete = profile == null ||
                                profile.firstName == "Pending" ||
                                profile.lastName == "User" ||
                                profile.handle.startsWith("user_") ||
                                profile.gender.isNullOrBlank() ||
                                profile.dob.isNullOrBlank()

                        if (isIncomplete) {
                            Log.d("GoogleAuth", "New Google user or incomplete profile. Directing to Onboarding...")
                            hasCompletedColdStartCheck = true
                            _isOnboarding.value = true
                        } else {
                            Log.d("GoogleAuth", "Existing Google user with complete profile. Proceeding to Feed...")
                            _isOnboarding.value = false
                            val newSessionId = UUID.randomUUID().toString()
                            withContext(Dispatchers.IO) {
                                try {
                                    supabaseClient.from("profiles").update(
                                        mapOf("active_session_id" to newSessionId)
                                    ) {
                                        filter { eq("id", userId) }
                                    }
                                } catch (_: Exception) {}
                            }
                            broadcastSessionChange(userId, newSessionId)
                            listenForSessionConflicts(userId, explicitSessionId = newSessionId)
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

                val response = supabaseClient.auth.signUpWith(Email) {
                    email = cleanEmail
                    password = tempPassword

                    data = buildJsonObject {
                        put("handle", tempHandle)
                        put("first_name", "Pending")
                        put("last_name", "User")
                    }
                }

                val identities = response?.identities
                if (identities != null && identities.isEmpty()) {
                    _errorMessage.value = "An account with this email already exists. Please log in."
                    return@launch
                }

                pendingEmail = cleanEmail
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

                supabaseClient.auth.verifyEmailOtp(
                    type = OtpType.Email.SIGNUP,
                    email = pendingEmail,
                    token = cleanCode
                )
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

        sessionConflictJob = null
        activeSessionChannel = null

        hasCompletedColdStartCheck = false
        localSessionId = ""
        _userState.value = null
        _isOnboarding.value = false
        _awaitingOtp.value = false
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

                supabaseClient.from("profiles").update(
                    {
                        set("gender", gender)
                        set("dob", dob)
                    }
                ) {
                    filter { eq("id", userId) }
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
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }

    fun saveOnboardingDetails(
        firstName: String,
        lastName: String,
        handle: String,
        gender: String,
        dob: String,
        realPassword: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                clearError()
                val currentUser = supabaseClient.auth.currentUserOrNull()
                val userId = currentUser?.id ?: return@launch
                val userEmail = currentUser.email

                val cleanHandle = handle.trim().removePrefix("@")

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

                supabaseClient.from("profiles").update(
                    {
                        set("first_name", firstName)
                        set("last_name", lastName.ifBlank { null })
                        set("handle", cleanHandle)
                        set("gender", gender.ifBlank { null })
                        set("dob", dob.ifBlank { null })
                        set("email", userEmail)
                    }
                ) {
                    filter { eq("id", userId) }
                }

                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = handleAuthError(e)
            }
        }
    }
}
