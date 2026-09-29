package com.example.agora.ui

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
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }

    val awaitingOtp by viewModel.awaitingOtp.collectAsState()
    val rawErrorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    LaunchedEffect(rawErrorMessage) {
        rawErrorMessage?.let { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
                viewModel.clearError()
            }
        }
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
                            Text(
                                text = "Verify Email",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 6.dp)
                            )
                            Text(
                                text = "We sent a 6-digit verification code to $email",
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
                                onClick = { viewModel.verifyOtpCode(otpCode) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = CircleShape,
                                enabled = otpCode.length == 6
                            ) {
                                Text(
                                    text = "Verify & Continue",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            TextButton(
                                onClick = { viewModel.resendOtp() },
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                Text(
                                    text = "Didn't get the code? Resend OTP",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
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

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val job = launch { snackbarHostState.showSnackbar("Verifying email...", duration = SnackbarDuration.Indefinite) }
                                        val isReal = EmailValidator.isEmailReal(email)
                                        job.cancel()

                                        if (isReal) viewModel.startRegistration(email)
                                        else snackbarHostState.showSnackbar("This email address does not appear to be active.")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = CircleShape,
                                enabled = email.isNotBlank()
                            ) {
                                Text(
                                    text = "Send Verification Code",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

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
