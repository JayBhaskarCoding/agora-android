package com.example.agora.navigation

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point for "open this post" navigation requests.
 *
 * FCM notification taps (background), foreground in-app banner taps and
 * cold/warm launch intents all funnel through here. [MainScreen] observes
 * [pending] and performs the NavController navigation once the NavHost exists,
 * which makes routing deterministic regardless of whether the process was
 * dead, the app was mid-auth, or the notification was tapped while the feed
 * was already on screen.
 *
 * Because [pending] is a StateFlow, a deep link submitted in
 * [com.example.agora.MainActivity.onCreate] (before the user is authenticated
 * and before MainScreen composes) is not lost — it is replayed to the first
 * observer.
 */
object DeepLinkRouter {

    /** A parsed "open post" request. */
    data class PendingPostLink(
        val postId: String,
        val commentId: String? = null
    ) {
        /** Route string matching MainScreen's `post/{postId}?commentId={commentId}` destination. */
        fun toRoute(): String =
            if (commentId.isNullOrBlank()) "post/$postId" else "post/$postId?commentId=$commentId"
    }

    private val _pending = MutableStateFlow<PendingPostLink?>(null)
    val pending: StateFlow<PendingPostLink?> = _pending.asStateFlow()

    fun submit(postId: String?, commentId: String? = null) {
        val cleanPostId = postId?.trim().orEmpty()
        if (cleanPostId.isEmpty()) return
        _pending.value = PendingPostLink(
            postId = cleanPostId,
            commentId = commentId?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /** Called by MainScreen after the link has been routed (or skipped as a duplicate). */
    fun consume(link: PendingPostLink) {
        _pending.compareAndSet(link, null)
    }

    /**
     * Extracts a post deep link from an [Intent], supporting both shapes:
     *  - `agora://post/{postId}[?commentId=…]` (custom scheme, notification taps)
     *  - `https://auth-agora.info/post/{postId}[?commentId=…]` (app links / share)
     * plus the legacy extras `post_id` / `comment_id` placed on the intent.
     *
     * Returns null for any other URI (e.g. Supabase auth callbacks).
     */
    fun parse(intent: Intent): PendingPostLink? {
        val extraPostId = intent.getStringExtra("post_id")?.trim().orEmpty()
        if (extraPostId.isNotEmpty()) {
            return PendingPostLink(
                postId = extraPostId,
                commentId = intent.getStringExtra("comment_id")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: intent.data?.getQueryParameter("commentId")
            )
        }

        val uri = intent.data ?: return null
        val postId = when {
            uri.scheme == "agora" && uri.host == "post" ->
                uri.pathSegments.firstOrNull()

            uri.host == "auth-agora.info" && uri.pathSegments.firstOrNull() == "post" ->
                uri.pathSegments.getOrNull(1)

            else -> null
        } ?: return null

        return PendingPostLink(
            postId = postId,
            commentId = uri.getQueryParameter("commentId")
                ?: uri.getQueryParameter("comment_id")
        )
    }
}
