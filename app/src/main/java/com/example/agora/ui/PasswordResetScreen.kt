package com.example.agora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.components.rememberOtpResendSeconds
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.utils.PasswordResetRouting
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.PasswordResetMode
import com.example.agora.viewmodel.PasswordResetStep
import kotlinx.coroutines.launch

/**
 * ✦ The three reset destinations. The graph is deliberately tiny and each route
 * refuses to resolve unless its precondition holds, so the only way forward is
 * the documented sequence:
 *
 *   EMAIL ──(code requested)──▶ OTP ──(code verified)──▶ NEW_PASSWORD
 *
 * *Change Password* (already authenticated) enters the graph at NEW_PASSWORD,
 * because the live session is itself proof of identity.
 */
object PasswordResetRoutes {
    const val EMAIL = "password_reset/email"
    const val OTP = "password_reset/otp"
    const val NEW_PASSWORD = "password_reset/new_password"

    fun routeFor(step: PasswordResetStep): String = when (step) {
        PasswordResetStep.EMAIL -> EMAIL
        PasswordResetStep.OTP -> OTP
        PasswordResetStep.NEW_PASSWORD -> NEW_PASSWORD
    }
}

/** 6-box code field shared by every reset OTP entry. */
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
    val mode by viewModel.passwordResetMode.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isBusy by viewModel.isPasswordResetBusy.collectAsState()
    val resendAvailableAt by viewModel.passwordResetResendAvailableAt.collectAsState()
    val codeRequested by viewModel.isRecoveryCodeRequested.collectAsState()
    val recoveryVerified by viewModel.isRecoverySessionVerified.collectAsState()

    // Same 60-second countdown the registration OTP card uses (shared helper).
    val secondsRemaining = rememberOtpResendSeconds(resendAvailableAt)

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    var emailInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }

    val canOpenOtp = PasswordResetRouting.canOpenOtpStep(mode, codeRequested)
    val canOpenNewPassword = PasswordResetRouting.canOpenNewPasswordStep(mode, recoveryVerified)

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

    // Back walks the steps in reverse and only leaves the flow from the first
    // one, so the router can never drop the user into a half-finished state.
    BackHandler {
        if (isBusy) return@BackHandler
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
            viewModel.editRecoveryEmail()
            otpInput = ""
        } else {
            viewModel.cancelPasswordReset()
            onCancel()
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
                        color = MaterialTheme.colorScheme.primary,
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
                        NavHost(
                            navController = navController,
                            startDestination = PasswordResetRoutes.routeFor(step ?: PasswordResetStep.EMAIL),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            composable(PasswordResetRoutes.EMAIL) {
                                if (mode == PasswordResetMode.RECOVERY) {
                                    // Step 1 → 2: only a *server-accepted* code
                                    // request advances the graph.
                                    LaunchedEffect(codeRequested) {
                                        if (codeRequested) {
                                            navController.navigate(PasswordResetRoutes.OTP) {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                    PasswordResetEmailStep(
                                        email = emailInput,
                                        onEmailChange = { emailInput = it; viewModel.clearError() },
                                        errorMessage = if (currentRoute == PasswordResetRoutes.EMAIL) errorMessage else null,
                                        isBusy = isBusy,
                                        onSendCode = { viewModel.requestPasswordResetOtp(emailInput) }
                                    )
                                } else {
                                    MissingRouteGuard(navController, PasswordResetRoutes.NEW_PASSWORD)
                                }
                            }

                            composable(PasswordResetRoutes.OTP) {
                                if (canOpenOtp) {
                                    // Step 2 → 3: gated on the verified recovery
                                    // session, never on the button tap alone.
                                    LaunchedEffect(recoveryVerified) {
                                        if (recoveryVerified) {
                                            navController.navigate(PasswordResetRoutes.NEW_PASSWORD) {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                    PasswordResetOtpStep(
                                        email = emailInput,
                                        otp = otpInput,
                                        onOtpChange = { otpInput = it; viewModel.clearError() },
                                        isError = errorMessage != null,
                                        isBusy = isBusy,
                                        secondsRemaining = secondsRemaining,
                                        onVerify = { viewModel.verifyPasswordResetOtp(otpInput) },
                                        onResend = { viewModel.requestPasswordResetOtp(emailInput) }
                                    )
                                } else {
                                    MissingRouteGuard(navController, PasswordResetRoutes.EMAIL)
                                }
                            }

                            composable(PasswordResetRoutes.NEW_PASSWORD) {
                                if (canOpenNewPassword) {
                                    PasswordResetNewPasswordStep(
                                        password = newPasswordInput,
                                        onPasswordChange = {
                                            newPasswordInput = it
                                            viewModel.clearError()
                                        },
                                        confirmPassword = confirmPasswordInput,
                                        onConfirmPasswordChange = {
                                            confirmPasswordInput = it
                                            viewModel.clearError()
                                        },
                                        isBusy = isBusy,
                                        onSubmit = {
                                            viewModel.submitNewPassword(newPasswordInput) {
                                                // Pop the reset destinations. For a
                                                // recovery the ViewModel has also
                                                // ended the temporary session, so
                                                // the shell lands on Login again.
                                                navController.popBackStack(
                                                    PasswordResetRoutes.EMAIL,
                                                    inclusive = true
                                                )
                                                onSuccess()
                                            }
                                        }
                                    )
                                } else {
                                    // No verified recovery session — send them back
                                    // to enter the code they were asked for.
                                    MissingRouteGuard(navController, PasswordResetRoutes.OTP)
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            viewModel.cancelPasswordReset()
                            onCancel()
                        },
                        enabled = !isBusy,
                        modifier = Modifier.padding(top = 20.dp)
                    ) {
                        Text(
                            text = if (mode == PasswordResetMode.CHANGE) "Cancel" else "Back to Log In",
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

/**
 * Renders nothing and returns to the step that owns the precondition. Every
 * destination that must not resolve out of order composes this instead of its
 * content, so even a programmatic navigate (or a future deep link) cannot skip
 * the code step.
 */
@Composable
private fun MissingRouteGuard(navController: NavHostController, fallbackRoute: String) {
    LaunchedEffect(fallbackRoute) {
        if (!navController.popBackStack(fallbackRoute, inclusive = false)) {
            navController.navigate(fallbackRoute) { launchSingleTop = true }
        }
    }
}
