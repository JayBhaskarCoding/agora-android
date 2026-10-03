package com.example.agora.ui

import com.example.agora.utils.OtpCooldown
import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.ui.components.AgoraPrimaryButton

/**
 * ✦ THE SHARED OTP CARD — one implementation for every 6-digit context:
 *  - manual email registration (embedded inside [RegisterScreen]'s card; the
 *    defaults verify through [AuthViewModel.verifyOtpCode] / resendOtp, which
 *    clears the challenge and resolves onboarding versus home), and
 *  - account-deletion reauthentication (hosted by [DeletionOtpScreen] with a
 *    custom title/subtitle/button and [AuthViewModel.verifyDeletionOtp] as the
 *    submission callback).
 *
 * Context is injected purely through parameters — the card itself is agnostic
 * to which flow it serves. Registration's gate remains a state-driven branch
 * (no back-stack destination), so nothing changes there.
 */
@Composable
fun OtpVerificationScreen(
    viewModel: AuthViewModel,
    email: String,
    modifier: Modifier = Modifier,
    title: String = "Verify Email",
    subtitle: String = "A 6-digit verification code was requested for $email. Check your inbox and spam folder.",
    buttonLabel: String = "Verify & Continue",
    onSubmit: (String) -> Unit = { viewModel.verifyOtpCode(it) },
    onResend: () -> Unit = { viewModel.resendOtp() },
    isBusy: Boolean = false,
    /** Registration supplies the server-accepted request deadline from its ViewModel. */
    resendAvailableAt: Long? = null
) {
    var otpCode by remember(email) { mutableStateOf("") }
    // Fallback for other users of the shared card (e.g. deletion verification).
    // Save the deadline, not a decrementing integer, so rotation/background time
    // cannot restart or pause the cooldown.
    var localDeadline by rememberSaveable(email) {
        mutableStateOf(SystemClock.elapsedRealtime() + OtpCooldown.DURATION_MS)
    }
    val deadline = resendAvailableAt ?: localDeadline
    fun remainingSeconds(): Int =
        OtpCooldown.remainingSeconds(deadline, SystemClock.elapsedRealtime())
    var secondsRemaining by remember(email, deadline) { mutableStateOf(remainingSeconds()) }

    LaunchedEffect(email, deadline) {
        secondsRemaining = remainingSeconds()
        while (secondsRemaining > 0) {
            delay(250L)
            secondsRemaining = remainingSeconds()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 6.dp)
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 24.dp)
        )

        // 🌟 6-Box OTP UI with translucent background
        BasicTextField(
            value = otpCode,
            enabled = !isBusy,
            onValueChange = { newValue ->
                if (newValue.length <= 6 && newValue.all { it in '0'..'9' }) {
                    otpCode = newValue
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            decorationBox = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    repeat(6) { index ->
                        val char = when {
                            index >= otpCode.length -> ""
                            else -> otpCode[index].toString()
                        }

                        val isFocused = otpCode.length == index || (otpCode.length == 6 && index == 5)

                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .border(
                                    width = if (isFocused) 2.dp else 1.dp,
                                    color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(28.dp))

        AgoraPrimaryButton(
            onClick = { onSubmit(otpCode) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = otpCode.length == 6 && !isBusy
        ) {
            Text(
                text = buttonLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(
            onClick = {
                if (isBusy || secondsRemaining > 0) return@TextButton
                onResend()
                // Registration resets only on server acceptance, not on failures.
                if (resendAvailableAt == null) localDeadline = SystemClock.elapsedRealtime() + OtpCooldown.DURATION_MS
            },
            enabled = secondsRemaining == 0 && !isBusy,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = if (secondsRemaining > 0) {
                    "Didn't receive a code? Send again in ${secondsRemaining}s"
                } else {
                    "Didn't receive a code? Send a new code"
                },
                color = if (secondsRemaining > 0 || isBusy) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
