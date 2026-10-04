package com.example.agora.ui

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
import com.example.agora.ui.components.AgoraPrimaryButton

/**
 * Recovery step 2 — the 6-digit code, with the shared 60-second resend
 * countdown. The screen cannot be left forward by tapping Verify: only the
 * ViewModel's verified recovery session unlocks the next destination.
 */
@Composable
fun PasswordResetOtpStep(
    email: String,
    otp: String,
    onOtpChange: (String) -> Unit,
    isError: Boolean,
    isBusy: Boolean,
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
            text = "We sent a 6-digit security code to $email.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 24.dp)
        )

        OtpInputField(
            otpText = otp,
            onOtpTextChange = onOtpChange,
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
            enabled = otp.length == 6 && !isBusy
        ) {
            Text(
                text = if (isBusy) "Verifying…" else "Verify Code",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(
            onClick = onResend,
            enabled = secondsRemaining == 0 && !isBusy,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = if (secondsRemaining > 0) {
                    "Send again in ${secondsRemaining}s"
                } else {
                    "Send a new code"
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
