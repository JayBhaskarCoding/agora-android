package com.example.agora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FcmTokenUpdate(
    @SerialName("fcm_token") val fcmToken: String
)
