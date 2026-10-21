package net.agora.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.ui.components.AgoraPrimaryButton
import net.agora.app.ui.components.agoraAuthFieldColors
import net.agora.app.ui.components.authSubtitleColor

/**
 * Password reset — step 3: choose the new password.
 *
 * Reachable only through `PasswordResetRouting.canOpenNewPasswordStep`, i.e.
 * with a live session (CHANGE) or a Supabase-verified recovery code (RECOVERY).
 * The mismatch/length hints here are feedback, not enforcement — the ViewModel
 * re-checks `PasswordPolicy` before it calls `updateUser`.
 */
@Composable
fun PasswordResetNewPasswordStep(
    newPasswordInput: String,
    isBusy: Boolean,
    onNewPasswordChange: (String) -> Unit,
    confirmPasswordInput: String,
    onConfirmPasswordChange: (String) -> Unit,
    isPasswordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    isConfirmPasswordVisible: Boolean,
    onToggleConfirmVisibility: () -> Unit,
    isDark: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "New Password",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 6.dp)
        )
        Text(
            text = "Enter your strong new password.",
            fontSize = 14.sp,
            color = authSubtitleColor(isDark),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = newPasswordInput,
            onValueChange = { onNewPasswordChange(it) },
            label = { Text("New Password") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF474C57)
                )
            },
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle Password Visibility"
                    )
                }
            },
            colors = agoraAuthFieldColors(isDark)
        )

        OutlinedTextField(
            value = confirmPasswordInput,
            onValueChange = { onConfirmPasswordChange(it) },
            label = { Text("Confirm New Password") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF474C57)
                )
            },
            trailingIcon = {
                IconButton(onClick = onToggleConfirmVisibility) {
                    Icon(
                        imageVector = if (isConfirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle Confirm Password Visibility"
                    )
                }
            },
            colors = agoraAuthFieldColors(isDark)
        )

        if (confirmPasswordInput.isNotEmpty() && confirmPasswordInput != newPasswordInput) {
            Text(
                text = "Passwords do not match.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 18.dp)
            )
        } else if (newPasswordInput.isNotEmpty() && newPasswordInput.length < 8) {
            Text(
                text = "Password must be at least 8 characters.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 18.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(18.dp))
        }

        AgoraPrimaryButton(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isBusy && newPasswordInput.length >= 8 && newPasswordInput == confirmPasswordInput
        ) {
            Text(
                text = "Update Password",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
