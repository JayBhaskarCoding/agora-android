package com.example.agora.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global bus for "this session was revoked because the account logged in on
 * another device" events.
 *
 * Any layer (the AuthViewModel's Realtime listener or its polling fallback) can
 * emit; the root UI observes [events] and shows the mandatory warning dialog
 * before the session is actually cleared.
 *
 * A SharedFlow (not StateFlow) is used so the event is edge-triggered: every
 * detection produces exactly one dialog, even if several checks fire at once.
 */
object SessionConflictRelay {

    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun notifySessionRevoked() {
        _events.tryEmit(Unit)
    }
}
