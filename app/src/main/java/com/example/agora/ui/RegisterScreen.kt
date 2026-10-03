package com.example.agora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.utils.EmailValidator
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.ui.components.AgoraPrimaryButton
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit,
    /** Preserves the pending address when the auth shell is recomposed. */
    initialOtpEmail: String? = null
) {
    var email by remember { mutableStateOf(initialOtpEmail.orEmpty()) }

    val awaitingOtp by viewModel.awaitingOtp.collectAsState()
    val otpEmail by viewModel.otpEmail.collectAsState()
    val isEmailOtpBusy by viewModel.isEmailOtpBusy.collectAsState()
    val resendAvailableAt by viewModel.emailOtpResendAvailableAt.collectAsState()
    var isCheckingEmail by remember { mutableStateOf(false) }

    BackHandler(enabled = awaitingOtp) {
        viewModel.cancelEmailVerification()
    }
    val rawErrorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    // 🌟 Ghost-account gate: this email belongs to a previously closed account
    //    whose posts were kept — the user must accept wiping them first.
    var showGhostDialog by remember { mutableStateOf(false) }

    LaunchedEffect(rawErrorMessage) {
        rawErrorMessage?.let { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
                viewModel.clearError()
            }
        }
    }

    if (showGhostDialog) {
        AlertDialog(
            onDismissRequest = { showGhostDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("Closed Account Found", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "An account previously registered with this email was closed, " +
                        "but its posts were kept. Proceeding will permanently delete " +
                        "all historic posts linked to this email."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGhostDialog = false
                        // Wipe the kept ghost posts first, then fire the standard
                        // registration OTP for the (now free) email.
                        viewModel.wipeHistoricGhostData(email) {
                            viewModel.startRegistration(email)
                        }
                    }
                ) {
                    Text(
                        "Proceed",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showGhostDialog = false }) {
                    Text(
                        "Cancel",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    VibrantGlassBackground(isDarkTheme = isDark) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // ✦ Gradient wordmark — same signature as the feed top bar.
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.onBackground,
                                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f)
                                        )
                                    ),
                                    fontWeight = FontWeight.Black
                                )
                            ) {
                                append("join agora")
                            }
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Black
                                )
                            ) {
                                append(".")
                            }
                        },
                        fontSize = 40.sp,
                        letterSpacing = (-1.1).sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Text(
                        text = "Connect, create, and share your world.",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 28.dp)
                    )

                    // 🌟 Frosted Glass Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassmorphic(
                                shape = RoundedCornerShape(26.dp),
                                isDark = isDark
                            )
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (awaitingOtp) {
                            // 🌟 Shared OTP gate (same composable the Google flow
                            //    shows at MainActivity level).
                            OtpVerificationScreen(
                                viewModel = viewModel,
                                email = otpEmail,
                                isBusy = isEmailOtpBusy,
                                resendAvailableAt = resendAvailableAt
                            )

                        } else {
                            Text(
                                text = "Get Started",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 6.dp)
                            )
                            Text(
                                text = "Enter your email to verify and begin.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 20.dp)
                            )

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it; viewModel.clearError() },
                                label = { Text("Email Address") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 24.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )

                            AgoraPrimaryButton(
                                onClick = {
                                    isCheckingEmail = true
                                    coroutineScope.launch {
                                        var lookupStarted = false
                                        try {
                                            val job = launch { snackbarHostState.showSnackbar("Verifying email...", duration = SnackbarDuration.Indefinite) }
                                            val isReal = try {
                                                EmailValidator.isEmailReal(email.trim())
                                            } finally {
                                                job.cancel()
                                            }

                                            if (!isReal) {
                                                snackbarHostState.showSnackbar("This email address does not appear to be active.")
                                                return@launch
                                            }

                                            // 🌟 Ghost intercept: if this email was attached to a
                                            //    previously closed account, confirm the historic-post
                                            //    wipe before the signup OTP is sent.
                                            lookupStarted = true
                                            viewModel.checkClosedAccountEmail(email) { isGhost ->
                                                isCheckingEmail = false
                                                if (isGhost) showGhostDialog = true
                                                else viewModel.startRegistration(email)
                                            }
                                        } finally {
                                            // The closed-account callback keeps the button
                                            // disabled until its lookup has completed.
                                            if (!lookupStarted) isCheckingEmail = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                enabled = email.isNotBlank() && !isEmailOtpBusy && !isCheckingEmail
                            ) {
                                Text(
                                    text = if (isEmailOtpBusy || isCheckingEmail) "Requesting code…" else "Send Verification Code",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (awaitingOtp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            TextButton(
                                onClick = { viewModel.cancelEmailVerification() },
                                enabled = !isEmailOtpBusy
                            ) {
                                Text(
                                    "Use a different email",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            TextButton(
                                onClick = {
                                    viewModel.cancelEmailVerification()
                                    onNavigateToLogin()
                                },
                                enabled = !isEmailOtpBusy
                            ) {
                                Text(
                                    "Back to Log In",
                                    color = if (isEmailOtpBusy) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { viewModel.clearError(); onNavigateToLogin() },
                            modifier = Modifier.padding(top = 22.dp)
                        ) {
                            Text(
                                text = "Already have an account? Log In",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
