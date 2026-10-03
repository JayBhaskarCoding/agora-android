package com.example.agora.utils

object OtpCooldown {
    const val DURATION_MS = 60_000L

    /** Round up: resend stays disabled for the entire final second. */
    fun remainingSeconds(availableAt: Long, now: Long): Int =
        ((availableAt - now).coerceAtLeast(0L).plus(999L) / 1000L).toInt()
}
