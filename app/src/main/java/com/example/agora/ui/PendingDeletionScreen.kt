package com.example.agora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.AuthViewModel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 🌟 Pending-deletion state for the 3-day account-deletion grace window.
 *
 * Shown whenever profiles.deletion_scheduled_at is set: states the exact
 * deletion deadline, explains what the grace period means (logout included),
 * and offers the prominent "Revert Changes" escape hatch which clears the
 * column back to NULL through the cancel_account_deletion RPC and returns
 * to the standard settings screen.
 *
 * Visual language: the shared Noir canvas (VibrantGlassBackground) with a
 * floating glassmorphic card — identical treatment in dark and light mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingDeletionScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit
) {
    val isDarkTheme = LocalDarkTheme.current
    val colors = rememberAgoraColors()
    val snackbarHostState = remember { SnackbarHostState() }

    val liveProfile by viewModel.profileState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var isReverting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshProfile() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    val scheduledAt = liveProfile?.deletionScheduledAt
    val formattedDate = remember(scheduledAt) { formatDeletionDate(scheduledAt) }

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
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Danger-tinted halo icon
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.error.copy(
                                    alpha = if (isDarkTheme) 0.16f else 0.10f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Account Scheduled for Deletion",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Your account is scheduled for deletion on",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = formattedDate ?: "the scheduled date",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "You have a 3-day grace period. Until then, reverting keeps " +
                            "your profile, posts and media exactly as they are. After the " +
                            "deadline, a background job permanently erases your account and " +
                            "everything attached to it — including if you log out in the " +
                            "meantime. This cannot be undone once it runs.",
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // ✦ Prominent revert action — the whole point of the grace window.
                    Button(
                        onClick = {
                            isReverting = true
                            viewModel.cancelAccountDeletion { reverted ->
                                isReverting = false
                                if (reverted) onBack()
                            }
                        },
                        enabled = !isReverting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape
                    ) {
                        if (isReverting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = "Revert Changes",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = onBack) {
                        Text(
                            text = "Back to Settings",
                            color = colors.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders the timestamptz stamp ("2026-10-03T09:41:00.123456+00:00") in the
 * device's zone as e.g. "Fri, 3 Oct 2026 at 3:11 PM". Falls back to the raw
 * string if parsing ever fails, so the deadline is never blank.
 */
private fun formatDeletionDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val formatter = DateTimeFormatter.ofPattern(
        "EEE, d MMM yyyy 'at' h:mm a",
        Locale.getDefault()
    )
    return try {
        OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(formatter)
    } catch (_: Exception) {
        try {
            Instant.parse(raw)
                .atZone(ZoneId.systemDefault())
                .format(formatter)
        } catch (_: Exception) {
            raw
        }
    }
}
