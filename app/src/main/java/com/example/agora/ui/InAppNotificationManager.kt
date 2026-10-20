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
import android.os.SystemClock
import com.example.agora.data.NotificationRepository
import com.example.agora.service.InAppNotification
import com.example.agora.service.NotificationRelay
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Window in which an identical notification arriving over the second transport
 * (Realtime StateFlow vs foreground FCM relay) is treated as a duplicate of the
 * banner already on screen and suppressed.
 */
private const val DELIVERY_DEDUPE_WINDOW_MS = 6_000L

/** Content key used to detect the same notification delivered twice. */
private fun InAppNotification.dedupeKey(): String = "$title|$body|$postId|$commentId"

@Composable
fun InAppNotificationManager(
    modifier: Modifier = Modifier,
    onNotificationClick: (postId: String, commentId: String?) -> Unit = { _, _ -> },
    content: @Composable () -> Unit
) {
    var activeNotification by remember { mutableStateOf<InAppNotification?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    // 🌟 TWO DELIVERY PATHS, ONE BANNER:
    //    1. NotificationRelay — foreground FCM data messages (Play Services devices)
    //    2. NotificationRepository.latestNotification — Supabase Realtime INSERTs
    //       on the `notifications` table (works on EVERY device, no FCM needed)
    //    The same database row can arrive through both transports within
    //    seconds of each other; the first delivery wins and its twin is
    //    suppressed via a short de-dupe window keyed on the visible content.
    var lastShownKey by remember { mutableStateOf<String?>(null) }
    var lastShownAt by remember { mutableLongStateOf(0L) }

    val showBanner: (InAppNotification) -> Unit = { notification ->
        val now = SystemClock.elapsedRealtime()
        val key = notification.dedupeKey()
        val isDuplicateTransportDelivery =
            key == lastShownKey && (now - lastShownAt) < DELIVERY_DEDUPE_WINDOW_MS
        if (!isDuplicateTransportDelivery) {
            lastShownKey = key
            lastShownAt = now
            activeNotification = notification
            isVisible = true
        }
    }

    // 🌟 Collect new notification events from relay (foreground FCM path)
    LaunchedEffect(Unit) {
        NotificationRelay.events.collect { notification ->
            showBanner(notification)
        }
    }

    // 🌟 Collect Realtime-driven notifications (Play-Services-independent path)
    LaunchedEffect(Unit) {
        NotificationRepository.latestNotification.collect { notification ->
            if (notification != null) {
                showBanner(notification)
                // Reset the StateFlow so the next insert — even an identical
                // one — emits again instead of being conflated.
                NotificationRepository.markLatestConsumed()
            }
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
