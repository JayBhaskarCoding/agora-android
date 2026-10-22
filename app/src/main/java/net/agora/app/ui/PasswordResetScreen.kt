package net.agora.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.ui.components.VibrantGlassBackground
import net.agora.app.ui.theme.LocalDarkTheme
import net.agora.app.ui.theme.glassmorphic
import net.agora.app.viewmodel.AuthViewModel
import net.agora.app.viewmodel.PasswordResetMode
import net.agora.app.viewmodel.PasswordResetStep
import net.agora.app.ui.components.rememberOtpResendSeconds
import kotlinx.coroutines.launch

@Composable
fun OtpInputField(
    otpText: String,
    onOtpTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    BasicTextField(
        value = otpText,
        onValueChange = { if (it.length <= 6 && it.all { char -> char.isDigit() }) onOtpTextChange(it) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier,
        decorationBox = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(6) { index ->
                    val digit = otpText.getOrNull(index)?.toString() ?: ""
                    val isFocused = otpText.length == index

                    val boxBorderColor = when {
                        isError -> MaterialTheme.colorScheme.error
                        isFocused -> MaterialTheme.colorScheme.primary
                        digit.isNotEmpty() -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            width = if (isFocused || isError) 2.dp else 1.dp,
                            color = boxBorderColor
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = digit,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordResetScreen(
    viewModel: AuthViewModel,
    onCancel: () -> Unit,
    onSuccess: () -> Unit
) {
    val step by viewModel.passwordResetStep.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Owned by the ViewModel so they survive configuration changes: the mode
    // decides the wording, `isBusy` stops a second tap from racing the
    // ViewModel's own guard, and the resend deadline keeps the cooldown honest
    // across rotation (it is an absolute monotonic instant, not a counter).
    val mode by viewModel.passwordResetMode.collectAsState()
    val isBusy by viewModel.isPasswordResetBusy.collectAsState()
    val resendAvailableAt by viewModel.passwordResetResendAvailableAt.collectAsState()
    val otpSecondsRemaining = rememberOtpResendSeconds(resendAvailableAt)

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    var emailInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isConfirmPasswordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            if (error != "This email does not match your active account." && step != PasswordResetStep.EMAIL) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Short)
                    viewModel.clearError()
                }
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
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (mode == PasswordResetMode.CHANGE) "Change Password" else "Reset Password",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.cancelPasswordReset()
                            onCancel()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.statusBarsPadding()
                )
            }
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
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Agora",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isDark) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            // Indigo on pearl: keep the brand hue readable.
                            Color(0xFF4338CA)
                        },
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    // 🌟 Frosted Glass Form Card
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
                        AnimatedContent(
                            targetState = step,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "PasswordResetStepTransition"
                        ) { currentStep ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                when (currentStep) {
                                    PasswordResetStep.EMAIL -> PasswordResetEmailStep(
                                        emailInput = emailInput,
                                        isBusy = isBusy,
                                        onEmailChange = { emailInput = it; viewModel.clearError() },
                                        // The screen decides which step an error belongs to; the step
                                        // composable only renders what it is handed.
                                        errorMessage = if (step == PasswordResetStep.EMAIL ||
                                            errorMessage == "This email does not match your active account."
                                        ) errorMessage else null,
                                        isDark = isDark,
                                        onSendCode = { viewModel.requestPasswordResetOtp(emailInput) }
                                    )

                                    PasswordResetStep.OTP -> PasswordResetOtpStep(
                                        emailInput = emailInput,
                                        isBusy = isBusy,
                                        otpInput = otpInput,
                                        onOtpChange = { otpInput = it; viewModel.clearError() },
                                        isError = errorMessage != null && step == PasswordResetStep.OTP,
                                        isDark = isDark,
                                        secondsRemaining = otpSecondsRemaining,
                                        onVerify = { viewModel.verifyPasswordResetOtp(otpInput) },
                                        onResend = { viewModel.requestPasswordResetOtp(emailInput) }
                                    )

                                    PasswordResetStep.NEW_PASSWORD -> PasswordResetNewPasswordStep(
                                        newPasswordInput = newPasswordInput,
                                        isBusy = isBusy,
                                        onNewPasswordChange = { newPasswordInput = it; viewModel.clearError() },
                                        confirmPasswordInput = confirmPasswordInput,
                                        onConfirmPasswordChange = { confirmPasswordInput = it; viewModel.clearError() },
                                        isPasswordVisible = isPasswordVisible,
                                        onTogglePasswordVisibility = { isPasswordVisible = !isPasswordVisible },
                                        isConfirmPasswordVisible = isConfirmPasswordVisible,
                                        onToggleConfirmVisibility = { isConfirmPasswordVisible = !isConfirmPasswordVisible },
                                        isDark = isDark,
                                        onSubmit = { viewModel.submitNewPassword(newPasswordInput, onSuccess) }
                                    )

                                    // `step` is `PasswordResetStep?`, so `currentStep` is
                                    // nullable even though MainActivity only composes this
                                    // screen while the step is non-null: cancelPasswordReset()
                                    // and the tail of submitNewPassword() set it to null, and
                                    // AnimatedContent still renders this lambda for the
                                    // outgoing frame while it fades. Nothing to draw.
                                    //
                                    // Deliberately `null ->` and not `else ->`: a fourth
                                    // step added to the enum must fail to compile here rather
                                    // than silently render an empty card.
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
