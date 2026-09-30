package com.example.agora.ui

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
import androidx.compose.material3.Button
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

/**
 * ✦ THE SHARED OTP CARD — one implementation for every 6-digit context:
 *  - manual email registration (embedded inside [RegisterScreen]'s card; the
 *    defaults verify through [AuthViewModel.verifyOtpCode] / resendOtp, which
 *    flips `awaitingOtp` off and `isOnboarding` on → enter-details screen), and
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
    subtitle: String = "We sent a 6-digit verification code to $email",
    buttonLabel: String = "Verify & Continue",
    onSubmit: (String) -> Unit = { viewModel.verifyOtpCode(it) },
    onResend: () -> Unit = { viewModel.resendOtp() }
) {
    var otpCode by remember { mutableStateOf("") }

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
            onValueChange = { newValue ->
                if (newValue.length <= 6 && newValue.all { it.isDigit() }) {
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

        Button(
            onClick = { onSubmit(otpCode) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = CircleShape,
            enabled = otpCode.length == 6
        ) {
            Text(
                text = buttonLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(
            onClick = { onResend() },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = "Didn't get the code? Resend OTP",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
