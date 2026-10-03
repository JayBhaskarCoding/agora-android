package com.example.agora.data

/** Transport boundary for email-code authentication (not password signup). */
interface EmailOtpGateway {
    suspend fun requestCode(email: String, createUser: Boolean)
    suspend fun verifyCode(email: String, code: String): String
}

/**
 * Keeps the pending address only after the server accepts an OTP request.
 * New, legacy unconfirmed and returning accounts all use the same flow.
 * A successful request is not a guarantee of inbox delivery.
 */
class EmailVerificationFlow(private val gateway: EmailOtpGateway) {
    var pendingEmail: String? = null
        private set

    suspend fun request(email: String) {
        gateway.requestCode(email, createUser = true)
        pendingEmail = email
    }

    suspend fun resend() {
        gateway.requestCode(requireNotNull(pendingEmail) { "No pending email verification found." }, createUser = false)
    }

    suspend fun verify(code: String): String {
        val userId = gateway.verifyCode(
            requireNotNull(pendingEmail) { "No pending email verification found." },
            code
        )
        pendingEmail = null
        return userId
    }

    fun cancel() {
        pendingEmail = null
    }
}
