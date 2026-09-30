package com.example.agora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.AuthViewModel

/**
 * 🌟 Deletion-context host for the shared OTP card.
 *
 * The user tapped "Delete Account" in Account Details, [AuthViewModel.startAccountDeletion]
 * fired Supabase's native `reauthenticate()` (the Reauthentication email template's
 * 6-digit code), and the flow routed here. The card is the exact same component the
 * registration flow uses — only the injected context differs:
 *
 *  - title:    "Verify Identity"
 *  - subtitle: explains the code guards account deletion
 *  - submit:   [AuthViewModel.verifyDeletionOtp] → verify_deletion_otp RPC verifies the
 *              code against GoTrue's reauthentication token hash and, on a match, stamps
 *              the 3-day deadline → [onVerified] routes to PendingDeletionScreen.
 *  - resend:   [AuthViewModel.resendDeletionOtp] (pure re-send, no navigation side effects)
 *
 * Wrong/expired codes surface through errorMessage → snackbar; the card stays up so the
 * user can retry. Visual language matches the rest of the auth suite: Noir canvas +
 * floating glassmorphic card in both themes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletionOtpScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onVerified: () -> Unit
) {
    val isDarkTheme = LocalDarkTheme.current
    val colors = rememberAgoraColors()
    val snackbarHostState = remember { SnackbarHostState() }

    val deletionEmail by viewModel.deletionOtpEmail.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    VibrantGlassBackground(isDarkTheme = isDarkTheme) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(64.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to settings",
                            tint = colors.textPrimary
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .glassmorphic(
                            shape = RoundedCornerShape(28.dp),
                            isDark = isDarkTheme
                        )
                        .padding(28.dp)
                ) {
                    OtpVerificationScreen(
                        viewModel = viewModel,
                        email = deletionEmail,
                        title = "Verify Identity",
                        subtitle = "To protect your account, we sent a 6-digit code to " +
                            "$deletionEmail. Enter it to confirm that you really want " +
                            "to schedule account deletion.",
                        buttonLabel = "Verify & Schedule Deletion",
                        onSubmit = { code ->
                            viewModel.verifyDeletionOtp(code) { onVerified() }
                        },
                        onResend = { viewModel.resendDeletionOtp() }
                    )
                }
            }
        }
    }
}
