package com.example.agora.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmailVerificationFlowTest {
    private class FakeGateway : EmailOtpGateway {
        val requests = mutableListOf<Pair<String, Boolean>>()
        var requestFailure: Exception? = null
        var verifyFailure: Exception? = null
        var verified: Pair<String, String>? = null

        override suspend fun requestCode(email: String, createUser: Boolean) {
            requests.add(email to createUser)
            requestFailure?.let { throw it }
        }

        override suspend fun verifyCode(email: String, code: String): String {
            verifyFailure?.let { throw it }
            verified = email to code
            return "verified-user-id"
        }
    }

    @Test
    fun requestsExplicitOtpAndResendsWithoutCreatingAnotherAccount() = runBlocking {
        val gateway = FakeGateway()
        val flow = EmailVerificationFlow(gateway)
        flow.request("returning@example.com")
        flow.resend()
        assertEquals(listOf("returning@example.com" to true, "returning@example.com" to false), gateway.requests)
        assertEquals("returning@example.com", flow.pendingEmail)
    }

    @Test
    fun failedRequestDoesNotOpenPendingVerification() = runBlocking {
        val gateway = FakeGateway().apply { requestFailure = IllegalStateException("SMTP unavailable") }
        val flow = EmailVerificationFlow(gateway)
        try {
            flow.request("new@example.com")
            fail("Expected request failure")
        } catch (_: IllegalStateException) {}
        assertNull(flow.pendingEmail)
    }

    @Test
    fun failedVerificationKeepsChallengeForRetry() = runBlocking {
        val gateway = FakeGateway()
        val flow = EmailVerificationFlow(gateway)
        flow.request("new@example.com")
        gateway.verifyFailure = IllegalStateException("Invalid code")
        try {
            flow.verify("111111")
            fail("Expected verification failure")
        } catch (_: IllegalStateException) {}
        assertEquals("new@example.com", flow.pendingEmail)
        gateway.verifyFailure = null
        assertEquals("verified-user-id", flow.verify("123456"))
        assertEquals("new@example.com" to "123456", gateway.verified)
        assertNull(flow.pendingEmail)
    }

    @Test
    fun failedResendKeepsPendingAddress() = runBlocking {
        val gateway = FakeGateway()
        val flow = EmailVerificationFlow(gateway)
        flow.request("new@example.com")
        gateway.requestFailure = IllegalStateException("Rate limited")
        try {
            flow.resend()
            fail("Expected resend failure")
        } catch (_: IllegalStateException) {}
        assertEquals("new@example.com", flow.pendingEmail)
    }

    @Test
    fun cancelAllowsDifferentEmailAndPreventsVerifyingOldChallenge() = runBlocking {
        val gateway = FakeGateway()
        val flow = EmailVerificationFlow(gateway)
        flow.request("old@example.com")
        flow.cancel()
        assertNull(flow.pendingEmail)
        try {
            flow.verify("123456")
            fail("Expected missing challenge failure")
        } catch (_: IllegalArgumentException) {}
        assertNull(gateway.verified)
        flow.request("new@example.com")
        flow.verify("654321")
        assertEquals("new@example.com" to "654321", gateway.verified)
    }
}
