package com.example.agora.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class InAppNotification(
    val title: String,
    val body: String,
    val senderId: String? = null,
    val postId: String? = null,
    val commentId: String? = null
)

object NotificationRelay {
    private val _events = MutableSharedFlow<InAppNotification>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

    fun emitNotification(notification: InAppNotification) {
        _events.tryEmit(notification)
    }
}
