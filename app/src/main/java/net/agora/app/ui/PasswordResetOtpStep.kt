package net.agora.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.ui.components.AgoraPrimaryButton
import net.agora.app.ui.components.authSubtitleColor

/**
 * Password reset — step 2: the 6-digit code.
 *
 * [secondsRemaining] comes from the shared `rememberOtpResendSeconds`, so this
 * step and the registration OTP card cannot drift on tick rate or on when the
 * cooldown expires. Tapping Verify cannot advance the flow by itself: the
 * ViewModel only moves to the next step after Supabase verifies the code and
 * mints the temporary recovery session.
 */
@Composable
fun PasswordResetOtpStep(
    emailInput: String,
    isBusy: Boolean,
    otpInput: String,
    onOtpChange: (String) -> Unit,
    isError: Boolean,
    isDark: Boolean,
    secondsRemaining: Int,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter Security Code",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 6.dp)
        )
        Text(
            text = "We sent a 6-digit security code to $emailInput.",
            fontSize = 14.sp,
            color = authSubtitleColor(isDark),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 24.dp)
        )

        OtpInputField(
            otpText = otpInput,
            onOtpTextChange = { onOtpChange(it) },
            isError = isError,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        AgoraPrimaryButton(
            onClick = onVerify,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isBusy && otpInput.length == 6
        ) {
            Text(
                text = "Verify Code",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(
            onClick = onResend,
            // Disabled while the shared cooldown is still running, so a
            // second tap cannot burn a Supabase send attempt or restart
            // the window.
            enabled = secondsRemaining == 0,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = if (secondsRemaining > 0) {
                    "Didn't receive a code? Send again in ${secondsRemaining}s"
                } else {
                    "Didn't receive code? Resend"
                },
                color = if (secondsRemaining > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
