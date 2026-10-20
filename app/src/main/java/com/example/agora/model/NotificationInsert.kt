package com.example.agora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationInsert(
    @SerialName("recipient_id") val recipientId: String,
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap()
)
