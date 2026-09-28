package com.example.agora.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.agora.MainActivity
import com.example.agora.R
import com.example.agora.data.supabaseClient
import com.example.agora.model.FcmTokenUpdate
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PushNotificationService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM Token: $token")

        val currentUser = supabaseClient.auth.currentUserOrNull()
        if (currentUser != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    supabaseClient.from("profiles").update(
                        FcmTokenUpdate(fcmToken = token)
                    ) {
                        filter { eq("id", currentUser.id) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // 🌟 1. Extract Data Payload (Title, Body, IDs, Post ID, Comment ID)
        val title = remoteMessage.data["title"]
            ?: remoteMessage.notification?.title
            ?: "Agora"

        val body = remoteMessage.data["body"]
            ?: remoteMessage.notification?.body
            ?: ""

        val authorId = remoteMessage.data["author_id"]
        val senderId = remoteMessage.data["sender_id"]
        val reporterId = remoteMessage.data["reporter_id"]
        val postId = remoteMessage.data["post_id"]
        val commentId = remoteMessage.data["comment_id"]

        // 🌟 2. The Filter: Compare author_id, sender_id, and reporter_id against current user
        val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
        if (currentUserId != null) {
            if ((authorId != null && authorId == currentUserId) ||
                (senderId != null && senderId == currentUserId) ||
                (reporterId != null && reporterId == currentUserId)) {
                Log.d(TAG, "Ignoring self-action notification for user: $currentUserId")
                return
            }
        }

        // 🌟 3. Foreground vs Background Routing via ProcessLifecycleOwner
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        if (isForeground) {
            // FOREGROUND: Emit to In-App Toast relay. Do NOT show system notification.
            Log.d(TAG, "App is in FOREGROUND: Emitting in-app toast for: $title")
            NotificationRelay.emitNotification(
                InAppNotification(
                    title = title,
                    body = body,
                    senderId = senderId ?: authorId ?: reporterId,
                    postId = postId,
                    commentId = commentId
                )
            )
        } else {
            // BACKGROUND: Manually construct and display standard system notification with deep link
            Log.d(TAG, "App is in BACKGROUND: Constructing system notification for: $title")
            showSystemNotification(title, body, postId, commentId)
        }
    }

    private fun showSystemNotification(
        title: String,
        message: String,
        postId: String?,
        commentId: String? = null
    ) {
        val channelId = CHANNEL_ID
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Ensure Notification Channel exists (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications for Agora messages and updates"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 🌟 Deep link straight to the post: agora://post/{postId}[?commentId=…]
        //    Explicit component + ACTION_VIEW so the URI is delivered via intent.data,
        //    matching the NavHost's navDeepLink patterns. Extras are kept as a
        //    redundant channel (parsed by MainActivity.injectDeepLink).
        val deepLinkUri = when {
            !postId.isNullOrBlank() && !commentId.isNullOrBlank() ->
                "agora://post/$postId?commentId=$commentId"
            !postId.isNullOrBlank() ->
                "agora://post/$postId"
            else -> null
        }

        val intent = if (deepLinkUri != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("post_id", postId)
                if (!commentId.isNullOrBlank()) {
                    putExtra("comment_id", commentId)
                }
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        // 🌟 TaskStackBuilder preserves the synthetic back stack:
        //    MainActivity (feed) -> Post details. Back never dumps the user out of the app.
        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(
                postId?.hashCode() ?: 0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(postId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val TAG = "PushNotificationService"
        const val CHANNEL_ID = "agora_notifications_channel"
        const val CHANNEL_NAME = "Agora Notifications"
    }
}
