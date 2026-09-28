package com.example.agora.utils

import android.util.Log
import com.example.agora.data.supabaseClient
import com.example.agora.model.NotificationInsert
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Inserts a row into the `notifications` table; the Supabase webhook forwards it
 * to the `push-notification` Edge Function which sends the FCM message.
 *
 * The `data` map is the routing contract with the Edge Function:
 *  - `post_id`    -> routed into the notification deep link (agora://post/{post_id})
 *  - `comment_id` -> optional, scroll-to-comment on the post screen
 *  - `sender_id` / `author_id` / `reporter_id` -> self-action filter on the client
 */
fun sendPushNotification(
    targetUserId: String,
    alertTitle: String,
    alertMessage: String,
    postId: String? = null,
    commentId: String? = null,
    senderId: String? = null
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val data = buildMap {
                senderId?.let { put("sender_id", it) }
                postId?.let { put("post_id", it) }
                commentId?.let { put("comment_id", it) }
            }

            val notification = NotificationInsert(
                recipientId = targetUserId,
                title = alertTitle,
                body = alertMessage,
                data = data
            )

            supabaseClient.from("notifications").insert(notification)
            Log.d("FCM", "Successfully inserted push notification for user: $targetUserId")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("FCM", "Failed to insert push notification: ${e.localizedMessage}", e)
        }
    }
}
