package net.agora.app.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.agora.app.utils.OtpCooldown
import kotlinx.coroutines.delay

// Ported from the retired template package history. Shared by the registration OTP
// screen and the password-reset OTP screen: both count down to an absolute
// monotonic deadline, so rotation, recomposition and backgrounding cannot
// restart or pause the cooldown.

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
