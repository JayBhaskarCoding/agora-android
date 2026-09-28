package com.example.agora.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agora.data.supabaseClient
import com.example.agora.model.Profile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ResetPasswordState { REQUEST_OTP, VERIFY_OTP, NEW_PASSWORD }

class ResetPasswordViewModel : ViewModel() {

    private val _currentStep = MutableStateFlow(ResetPasswordState.REQUEST_OTP)
    val currentStep: StateFlow<ResetPasswordState> = _currentStep.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _otp = MutableStateFlow("")
    val otp: StateFlow<String> = _otp.asStateFlow()

    private val _newPassword = MutableStateFlow("")
    val newPassword: StateFlow<String> = _newPassword.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun onEmailChange(input: String) {
        _email.value = input
        _errorMessage.value = null
    }

    fun onOtpChange(input: String) {
        if (input.length <= 6) {
            _otp.value = input
            _errorMessage.value = null
        }
    }

    fun onNewPasswordChange(input: String) {
        _newPassword.value = input
        _errorMessage.value = null
    }

    fun onConfirmPasswordChange(input: String) {
        _confirmPassword.value = input
        _errorMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun sendOtp(onSuccess: () -> Unit = {}) {
        val currentEmail = _email.value.trim()
        if (currentEmail.isBlank()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Active Session Email Validation (if user is currently logged in)
                val currentSessionUser = supabaseClient.auth.currentUserOrNull()
                if (currentSessionUser != null && currentSessionUser.email != null) {
                    val activeSessionEmail = currentSessionUser.email!!
                    if (!currentEmail.equals(activeSessionEmail, ignoreCase = true)) {
                        _errorMessage.value = "This email does not match your active account."
                        return@launch
                    }
                }

                // Pre-verification check: Query Supabase to verify account existence
                val existingProfiles = supabaseClient.from("profiles")
                    .select { filter { eq("email", currentEmail) } }
                    .decodeList<Profile>()

                if (existingProfiles.isEmpty()) {
                    _errorMessage.value = "No account found with this email address."
                    return@launch
                }

                supabaseClient.auth.resetPasswordForEmail(currentEmail)
                _currentStep.value = ResetPasswordState.VERIFY_OTP
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to send OTP."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun verifyOtp(onSuccess: () -> Unit = {}) {
        val currentOtp = _otp.value.trim()
        if (currentOtp.length != 6) {
            _errorMessage.value = "Please enter the 6-digit security code."
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                // TODO: Inject Supabase / Backend verify OTP logic here
                _currentStep.value = ResetPasswordState.NEW_PASSWORD
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Invalid security code."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updatePassword(onSuccess: () -> Unit = {}) {
        val pass = _newPassword.value
        val confirm = _confirmPassword.value

        if (pass.length < 8) {
            _errorMessage.value = "Password must be at least 8 characters long."
            return
        }
        if (pass != confirm) {
            _errorMessage.value = "Passwords do not match."
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                // TODO: Inject Supabase / Backend update password logic here
                _currentStep.value = ResetPasswordState.REQUEST_OTP
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update password."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun goToPreviousStep() {
        when (_currentStep.value) {
            ResetPasswordState.REQUEST_OTP -> { /* Base step */ }
            ResetPasswordState.VERIFY_OTP -> _currentStep.value = ResetPasswordState.REQUEST_OTP
            ResetPasswordState.NEW_PASSWORD -> _currentStep.value = ResetPasswordState.VERIFY_OTP
        }
    }

    fun resetFlow() {
        _currentStep.value = ResetPasswordState.REQUEST_OTP
        _email.value = ""
        _otp.value = ""
        _newPassword.value = ""
        _confirmPassword.value = ""
        _errorMessage.value = null
        _isLoading.value = false
    }
}
