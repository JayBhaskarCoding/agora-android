package com.example.agora.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.agora.utils.OtpCooldown
import kotlinx.coroutines.delay

/**
 * ✦ THE SHARED RESEND COUNTDOWN — seconds left before a new code may be
 * requested, or `0` once the cooldown has expired.
 *
 * [deadline] is an absolute monotonic timestamp ([SystemClock.elapsedRealtime])
 * computed when the backend accepted a send request, never a decrementing
 * counter: rotation, recomposition and backgrounding therefore cannot restart
 * or pause the cooldown, and the device's wall clock cannot shorten it. Ticking
 * follows [LaunchedEffect], so the coroutine stops as soon as the deadline
 * passes and restarts cleanly when the deadline moves.
 */
@Composable
fun rememberOtpResendSeconds(deadline: Long, tickMillis: Long = 250L): Int {
    var seconds by remember(deadline) {
        mutableStateOf(OtpCooldown.remainingSeconds(deadline, SystemClock.elapsedRealtime()))
    }

    LaunchedEffect(deadline) {
        while (true) {
            val remaining = OtpCooldown.remainingSeconds(deadline, SystemClock.elapsedRealtime())
            seconds = remaining
            if (remaining <= 0) break
            delay(tickMillis)
        }
    }

    return seconds
}
