package com.example.agora.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.PasswordResetStep
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
                    title = { Text("Reset Password", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) },
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
                                    PasswordResetStep.EMAIL -> {
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .align(Alignment.Start)
                                                .padding(bottom = 24.dp)
                                        )

                                        val isEmailError = errorMessage != null && (step == PasswordResetStep.EMAIL || errorMessage == "This email does not match your active account.")

                                        OutlinedTextField(
                                            value = emailInput,
                                            onValueChange = { emailInput = it; viewModel.clearError() },
                                            label = { Text("Email Address") },
                                            singleLine = true,
                                            isError = isEmailError,
                                            shape = RoundedCornerShape(16.dp),
                                            supportingText = {
                                                if (isEmailError && errorMessage != null) {
                                                    Text(
                                                        text = errorMessage!!,
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
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent
                                            )
                                        )

                                        Button(
                                            onClick = { viewModel.requestPasswordResetOtp(emailInput) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            enabled = emailInput.isNotBlank()
                                        ) {
                                            Text(
                                                text = "Send Code",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    PasswordResetStep.OTP -> {
                                        val isOtpError = errorMessage != null && step == PasswordResetStep.OTP

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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .align(Alignment.Start)
                                                .padding(bottom = 24.dp)
                                        )

                                        OtpInputField(
                                            otpText = otpInput,
                                            onOtpTextChange = { otpInput = it; viewModel.clearError() },
                                            isError = isOtpError,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 24.dp)
                                        )

                                        Button(
                                            onClick = { viewModel.verifyPasswordResetOtp(otpInput) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            enabled = otpInput.length == 6
                                        ) {
                                            Text(
                                                text = "Verify Code",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        TextButton(
                                            onClick = { viewModel.requestPasswordResetOtp(emailInput) },
                                            modifier = Modifier.padding(top = 16.dp)
                                        ) {
                                            Text(
                                                text = "Didn't receive code? Resend",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    PasswordResetStep.NEW_PASSWORD -> {
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .align(Alignment.Start)
                                                .padding(bottom = 24.dp)
                                        )

                                        OutlinedTextField(
                                            value = newPasswordInput,
                                            onValueChange = { newPasswordInput = it; viewModel.clearError() },
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
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                                    Icon(
                                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                        contentDescription = "Toggle Password Visibility"
                                                    )
                                                }
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent
                                            )
                                        )

                                        OutlinedTextField(
                                            value = confirmPasswordInput,
                                            onValueChange = { confirmPasswordInput = it; viewModel.clearError() },
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
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible }) {
                                                    Icon(
                                                        imageVector = if (isConfirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                        contentDescription = "Toggle Confirm Password Visibility"
                                                    )
                                                }
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent
                                            )
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

                                        Button(
                                            onClick = { viewModel.submitNewPassword(newPasswordInput, onSuccess) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            enabled = newPasswordInput.length >= 8 && newPasswordInput == confirmPasswordInput
                                        ) {
                                            Text(
                                                text = "Update Password",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
