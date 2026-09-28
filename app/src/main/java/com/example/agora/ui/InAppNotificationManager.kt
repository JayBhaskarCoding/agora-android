package com.example.agora.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.service.InAppNotification
import com.example.agora.service.NotificationRelay
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun InAppNotificationManager(
    modifier: Modifier = Modifier,
    onNotificationClick: (postId: String, commentId: String?) -> Unit = { _, _ -> },
    content: @Composable () -> Unit
) {
    var activeNotification by remember { mutableStateOf<InAppNotification?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    // 🌟 Collect new notification events from relay
    LaunchedEffect(Unit) {
        NotificationRelay.events.collect { notification ->
            activeNotification = notification
            isVisible = true
        }
    }

    // 🌟 Smooth 5-second display + 500ms exit animation timing
    LaunchedEffect(activeNotification) {
        if (activeNotification != null) {
            isVisible = true
            delay(5000.milliseconds) // Display for 5 full seconds
            isVisible = false        // Triggers slide-out exit animation
            delay(500.milliseconds)  // Wait 500ms for exit animation to complete
            activeNotification = null // Clear data after animation finishes
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        content()

        // 🌟 INSTAGRAM-STYLE TOP IN-APP TOAST BANNER
        AnimatedVisibility(
            visible = isVisible && activeNotification != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
        ) {
            activeNotification?.let { notification ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clickable {
                            val pId = notification.postId
                            if (!pId.isNullOrBlank()) {
                                onNotificationClick(pId, notification.commentId)
                                isVisible = false
                                activeNotification = null
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Notification Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = notification.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = notification.body,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
