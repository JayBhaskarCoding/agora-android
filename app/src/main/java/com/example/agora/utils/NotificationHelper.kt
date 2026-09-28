package com.example.agora.utils

import android.util.Log
import com.example.agora.data.supabaseClient
import com.example.agora.model.NotificationInsert
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun sendPushNotification(
    targetUserId: String,
    alertTitle: String,
    alertMessage: String
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val notification = NotificationInsert(
                recipientId = targetUserId,
                title = alertTitle,
                body = alertMessage
            )

            supabaseClient.from("notifications").insert(notification)
            Log.d("FCM", "Successfully inserted push notification for user: $targetUserId")
        } catch (e: Exception) {
            Log.e("FCM", "Failed to insert push notification: ${e.localizedMessage}", e)
        }
    }
}
