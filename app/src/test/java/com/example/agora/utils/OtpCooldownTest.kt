package com.example.agora.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class OtpCooldownTest {
    @Test
    fun startsAtSixtySeconds() {
        assertEquals(60, OtpCooldown.remainingSeconds(61_000L, 1_000L))
    }

    @Test
    fun roundsUpUntilDeadline() {
        assertEquals(60, OtpCooldown.remainingSeconds(61_000L, 1_001L))
        assertEquals(59, OtpCooldown.remainingSeconds(61_000L, 2_000L))
        assertEquals(1, OtpCooldown.remainingSeconds(61_000L, 60_999L))
    }

    @Test
    fun expiredDeadlineNeverGoesNegative() {
        assertEquals(0, OtpCooldown.remainingSeconds(61_000L, 61_000L))
        assertEquals(0, OtpCooldown.remainingSeconds(61_000L, 120_000L))
    }

    @Test
    fun backgroundTimeCountsTowardCooldown() {
        assertEquals(15, OtpCooldown.remainingSeconds(61_000L, 46_000L))
    }

    @Test
    fun successfulResendCanStartNewFullCooldown() {
        val now = 61_000L
        assertEquals(60, OtpCooldown.remainingSeconds(now + OtpCooldown.DURATION_MS, now))
    }
}
