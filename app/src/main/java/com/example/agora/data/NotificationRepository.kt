package com.example.agora.data

import android.util.Log
import com.example.agora.service.InAppNotification
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 🌟 PLAY-SERVICES-INDEPENDENT IN-APP NOTIFICATIONS.
 *
 * FCM requires Google Play Services; on devices/emulators without it the
 * registration token fails (MISSING_INSTANCEID_SERVICE) and no push ever
 * arrives, which used to take the foreground in-app banner down with it.
 *
 * This repository owns a second, transport-independent delivery path: a
 * Supabase Realtime subscription to INSERT events on the `notifications`
 * table, filtered to `recipient_id = <current user>`. Every new row is
 * mapped to an [InAppNotification] and published through [latestNotification]
 * (a [StateFlow]), which the in-app banner UI collects directly — no FCM,
 * no Edge Function, no Play Services involved.
 *
 * Lifecycle: [startRealtimeNotifications] is called as soon as the session is
 * authenticated (idempotent per user) and [stopRealtimeNotifications] on
 * logout / session loss. The FCM foreground path in PushNotificationService is
 * left intact as a redundant transport; the UI de-duplicates identical
 * notifications arriving through both paths within a short window.
 *
 * Server-side prerequisites (one-time, outside this app):
 *  - `notifications` must be in the `supabase_realtime` publication
 *    (ALTER PUBLICATION supabase_realtime ADD TABLE notifications;)
 *  - RLS must allow SELECT on a user's own rows (recipient_id = auth.uid()),
 *    otherwise Realtime silently delivers nothing.
 */
object NotificationRepository {

    private const val TAG = "NotificationRealtime"

    private val _latestNotification = MutableStateFlow<InAppNotification?>(null)

    /**
     * Foreground in-app notification UI state, driven purely by Supabase
     * Realtime INSERTs. Emits `null` after the UI consumes a banner (see
     * [markLatestConsumed]) so an identical notification can re-trigger later.
     */
    val latestNotification: StateFlow<InAppNotification?> = _latestNotification.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var collectJob: Job? = null
    private var subscribeJob: Job? = null
    private var channel: RealtimeChannel? = null
    private var subscribedUserId: String? = null
    private var startCounter = 0L

    /**
     * Opens (or reuses) the Realtime subscription for the currently
     * authenticated user. Safe to call repeatedly: a live subscription for the
     * same user is left untouched. Never throws — any failure is logged and
     * the app continues; FCM keeps working in parallel where available.
     */
    @Synchronized
    fun startRealtimeNotifications() {
        val userId = try {
            supabaseClient.auth.currentUserOrNull()?.id
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve current user; skipping realtime notifications: ${e.message}")
            return
        }
        if (userId == null) {
            Log.w(TAG, "No authenticated user; skipping realtime notification subscription")
            return
        }
        if (userId == subscribedUserId && collectJob?.isActive == true) {
            return // already live for this user
        }

        // Tear down any previous (possibly different-user) subscription first.
        stopInternal()

        subscribedUserId = userId

        try {
            // Unique id per start: realtime.channel(id) would otherwise hand
            // back a stale registered channel, and postgresChangeFlow throws
            // when called on an already-subscribed channel.
            val channelId = "notifications-user-$userId-${startCounter++}"
            val realtimeChannel = supabaseClient.realtime.channel(channelId)
            channel = realtimeChannel

            // NOTE: must be created BEFORE subscribe() — the join payload
            // carries the postgres_changes config registered here.
            val insertFlow = realtimeChannel.postgresChangeFlow<PostgresAction.Insert>(
                schema = "public"
            ) {
                table = "notifications"
                filter("recipient_id", FilterOperator.EQ, userId)
            }

            collectJob = scope.launch {
                try {
                    insertFlow.collect { insert ->
                        // Per-record guard: one malformed row must never kill
                        // the subscription stream.
                        val notification = try {
                            insert.record.toInAppNotification(userId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping unparseable notification record: ${e.message}")
                            null
                        } ?: return@collect
                        _latestNotification.value = notification
                        Log.d(TAG, "Realtime INSERT -> in-app notification: ${notification.title}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Realtime notification stream failed: ${e.localizedMessage}", e)
                }
            }

            subscribeJob = scope.launch {
                try {
                    realtimeChannel.subscribe(blockUntilSubscribed = true)
                    Log.d(TAG, "Subscribed to realtime INSERTs on 'notifications' for user $userId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Non-fatal: the app still works (and FCM covers Play
                    // Services devices). Next session change retries the start.
                    Log.e(
                        TAG,
                        "Realtime subscribe failed — in-app notifications may be delayed: ${e.localizedMessage}",
                        e
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not set up realtime notifications: ${e.localizedMessage}", e)
            subscribedUserId = null
        }
    }

    /** Cancels the subscription and clears UI state. Safe to call anytime. */
    @Synchronized
    fun stopRealtimeNotifications() {
        stopInternal()
        subscribedUserId = null
    }

    /**
     * Called by the banner UI after consuming [latestNotification]; resets the
     * StateFlow to null so the next notification (even an identical one) emits
     * again instead of being conflated.
     */
    fun markLatestConsumed() {
        _latestNotification.value = null
    }

    private fun stopInternal() {
        collectJob?.cancel()
        collectJob = null
        subscribeJob?.cancel()
        subscribeJob = null
        val oldChannel = channel
        channel = null
        if (oldChannel != null) {
            scope.launch {
                try {
                    // removeChannel unsubscribes AND drops it from the plugin
                    // registry, so a later start() can never reuse a stale one.
                    supabaseClient.realtime.removeChannel(oldChannel)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove realtime channel: ${e.message}")
                }
            }
        }
        _latestNotification.value = null
    }

    /**
     * Maps a raw `notifications` row (Realtime INSERT record) onto the banner
     * model. Mirrors the payload contract of PushNotificationService: the
     * `data` jsonb column carries post_id / comment_id / sender_id routing.
     * Returns null for self-actions (same filter the FCM path applies) and for
     * rows without any displayable content.
     */
    private fun JsonObject.toInAppNotification(currentUserId: String): InAppNotification? {
        // Safe casts throughout: DB NULLs arrive as JsonNull and the
        // jsonPrimitive/jsonObject accessors throw on unexpected shapes.
        val title = (this["title"] as? JsonPrimitive)?.contentOrNull
        val body = (this["body"] as? JsonPrimitive)?.contentOrNull
        if (title.isNullOrBlank() && body.isNullOrBlank()) {
            Log.w(TAG, "Ignoring realtime notification row without title/body")
            return null
        }

        val data = (this["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val senderId = (data["sender_id"] as? JsonPrimitive)?.contentOrNull
            ?: (data["author_id"] as? JsonPrimitive)?.contentOrNull
            ?: (data["reporter_id"] as? JsonPrimitive)?.contentOrNull

        if (senderId != null && senderId == currentUserId) {
            Log.d(TAG, "Ignoring self-action realtime notification for user: $currentUserId")
            return null
        }

        return InAppNotification(
            title = title ?: "Agora",
            body = body ?: "",
            senderId = senderId,
            postId = (data["post_id"] as? JsonPrimitive)?.contentOrNull,
            commentId = (data["comment_id"] as? JsonPrimitive)?.contentOrNull
        )
    }
}
