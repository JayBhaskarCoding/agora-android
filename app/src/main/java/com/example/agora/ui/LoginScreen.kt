package com.example.agora.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.R
import com.example.agora.ui.components.AuthWordmarkSizes
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.components.agoraAuthFieldColors
import com.example.agora.ui.components.authSubtitleColor
import com.example.agora.ui.components.rememberAgoraWordmark
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.ui.components.AgoraPrimaryButton
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onNavigateToRegister: () -> Unit
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val rawErrorMessage by authViewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isDark = LocalDarkTheme.current

    LaunchedEffect(rawErrorMessage) {
        rawErrorMessage?.let { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
                authViewModel.clearError()
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
                    // ✦ Wordmark — crisp ink in Light Mode, glow-free in Dark.
                    Text(
                        text = rememberAgoraWordmark("agora"),
                        fontSize = AuthWordmarkSizes.Login,
                        letterSpacing = (-1.2).sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Text(
                        text = "Enter the social arena.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = authSubtitleColor(isDark),
                        modifier = Modifier.padding(bottom = 28.dp)
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
                        Text(
                            text = "Welcome Back",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(bottom = 20.dp)
                        )

                        OutlinedTextField(
                            value = identifier,
                            onValueChange = { identifier = it; authViewModel.clearError() },
                            label = { Text("Email or Username") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = agoraAuthFieldColors(isDark)
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; authViewModel.clearError() },
                            label = { Text("Password") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = agoraAuthFieldColors(isDark)
                        )

                        if (password.isNotEmpty() && password.length < 8) {
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
                            onClick = { authViewModel.signIn(identifier, password) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            enabled = identifier.isNotBlank() && password.length >= 8
                        ) {
                            Text(
                                text = "Log In",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 🌟 Divider
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.25f else 0.55f)
                            )
                            Text(
                                text = "or continue with",
                                fontSize = 12.sp,
                                color = authSubtitleColor(isDark),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.25f else 0.55f)
                            )
                        }

                        // 🌟 GOOGLE SIGN-IN BUTTON
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    // The Web OAuth client id comes from
                                    // BuildConfig.GOOGLE_WEB_CLIENT_ID — never a
                                    // literal here, so debug/release/staging can
                                    // never disagree with the ViewModel.
                                    authViewModel.signInWithGoogle(context = context)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = CircleShape,
                            border = BorderStroke(
                                1.dp,
                                if (isDark) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onBackground
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_google),
                                    contentDescription = "Google Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Sign in with Google",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            authViewModel.clearError()
                            onNavigateToRegister()
                        },
                        modifier = Modifier.padding(top = 22.dp)
                    ) {
                        Text(
                            text = "Don't have an account? Sign Up",
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
