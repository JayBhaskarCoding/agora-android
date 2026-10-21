package net.agora.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.ui.components.AgoraPrimaryButton
import net.agora.app.ui.components.agoraAuthFieldColors
import net.agora.app.ui.components.authSubtitleColor

/**
 * Password reset — step 1: ask for the address the recovery code goes to.
 *
 * Extracted from `PasswordResetScreen`'s inline `when` branch so the flow's
 * three steps live in their own files (the layout the never-merged branch had),
 * while keeping the CURRENT body — the older extracted copy predated the
 * light-mode contrast work (`agoraAuthFieldColors` / `authSubtitleColor`) and
 * would have reverted it.
 *
 * Purely presentational: the screen only advances when the ViewModel reports
 * that Supabase accepted the request.
 */
@Composable
fun PasswordResetEmailStep(
    emailInput: String,
    isBusy: Boolean,
    onEmailChange: (String) -> Unit,
    errorMessage: String?,
    isDark: Boolean,
    onSendCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Verify Account",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 6.dp)
        )
        Text(
            text = "Enter your email to receive a 6-digit security code.",
            fontSize = 14.sp,
            color = authSubtitleColor(isDark),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = emailInput,
            onValueChange = { onEmailChange(it) },
            label = { Text("Email Address") },
            singleLine = true,
            isError = errorMessage != null,
            shape = RoundedCornerShape(16.dp),
            supportingText = {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            leadingIcon = {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF474C57)
                )
            },
            colors = agoraAuthFieldColors(isDark)
        )

        AgoraPrimaryButton(
            onClick = onSendCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isBusy && emailInput.isNotBlank()
        ) {
            Text(
                text = "Send Code",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
