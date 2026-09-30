package com.example.agora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.glassmorphic
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.AuthViewModel

/**
 * 🌟 Step 3 of account deletion — shown right after the reauthentication OTP was
 * verified and the 3-day deadline stamped. The user picks what the scheduled
 * backend job will actually do:
 *
 *  - Close Account but Keep Posts (soft): profile PII is wiped and the author is
 *    masked as "Removed User" everywhere, posts stay live, username stays locked,
 *    login is permanently banned.
 *  - Delete Everything (hard): the full erase pipeline — profile, posts, likes,
 *    comments and all interactions gone for good.
 *
 * The choice flows through [AuthViewModel.chooseDeletionMode] into the
 * choose_deletion_mode RPC (which refuses unless deletion is already verified),
 * then [onScheduled] routes to PendingDeletionScreen. Visual language matches
 * the rest of the deletion suite: Noir canvas + floating glassmorphic cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletionModeScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onScheduled: () -> Unit
) {
    val isDarkTheme = LocalDarkTheme.current
    val colors = rememberAgoraColors()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorMessage by viewModel.errorMessage.collectAsState()

    // Which card (if any) is mid-request: null = idle, true = soft, false = hard.
    var pendingChoice by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
            // The RPC failed — unlock the cards so the user can retry.
            pendingChoice = null
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
                    IconButton(onClick = { if (pendingChoice == null) onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to settings",
                            tint = colors.textPrimary
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                // ✦ Header
                Text(
                    text = "One Last Choice",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Your identity is verified and deletion is scheduled 3 days " +
                        "from now. How much should be removed when the deadline hits?",
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(26.dp))

                // ✦ Option A — soft close
                DeletionModeCard(
                    icon = Icons.Filled.PersonOff,
                    accent = colors.accent,
                    title = "Close Account but Keep Posts",
                    body = "Your profile details, likes, and comments will be wiped. " +
                        "Your posts will remain visible under a \"Removed User\" alias. " +
                        "Your username remains locked.",
                    isDark = isDarkTheme,
                    textPrimary = colors.textPrimary,
                    textSecondary = colors.textSecondary,
                    enabled = pendingChoice == null,
                    showSpinner = pendingChoice == true,
                    onClick = {
                        pendingChoice = true
                        viewModel.chooseDeletionMode(isSoftDelete = true) { onScheduled() }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ✦ Option B — hard erase
                DeletionModeCard(
                    icon = Icons.Filled.DeleteForever,
                    accent = MaterialTheme.colorScheme.error,
                    title = "Delete Everything",
                    body = "Your profile, posts, likes, comments, and all interactions " +
                        "will be permanently erased.",
                    isDark = isDarkTheme,
                    textPrimary = colors.textPrimary,
                    textSecondary = colors.textSecondary,
                    enabled = pendingChoice == null,
                    showSpinner = pendingChoice == false,
                    onClick = {
                        pendingChoice = false
                        viewModel.chooseDeletionMode(isSoftDelete = false) { onScheduled() }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "You can still revert from Account Details any time within " +
                        "the 3-day grace period.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** One selectable glassmorphic mode card: tinted icon chip, title, body, chevron. */
@Composable
private fun DeletionModeCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    isDark: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    enabled: Boolean,
    showSpinner: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassmorphic(
                shape = RoundedCornerShape(24.dp),
                isDark = isDark
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ✦ Tinted icon chip
        Box(
            modifier = Modifier
                .size(48.dp)
                .glassmorphic(shape = CircleShape, isDark = isDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = body,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = textSecondary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = accent
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = textSecondary.copy(alpha = if (enabled) 0.7f else 0.3f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
