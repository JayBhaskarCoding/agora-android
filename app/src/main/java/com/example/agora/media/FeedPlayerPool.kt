package com.example.agora.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Free-list pool of ExoPlayer instances for the feed.
 *
 * Creating + preparing an ExoPlayer per video item is the single most expensive
 * part of scrolling a media feed (decoder allocation, prepare, GC churn). This
 * pool keeps up to [POOL_SIZE] warm players on a free list and rebinds them to
 * new URLs as the user scrolls; players in active use are never stolen, so a
 * visible cell can't lose its player to a prefetch. A player is only
 * [ExoPlayer.release]-d when it is returned while the free list is full (or on
 * [releaseAll]).
 *
 * Mute state is session-global (like TikTok/Instagram) instead of per-item —
 * it survives scrolling and avoids per-item saved-state churn.
 */
@OptIn(UnstableApi::class)
object FeedPlayerPool {

    /** Warm players kept ready for reuse (≈ max visible feed videos). */
    private const val POOL_SIZE = 2

    /** Shared, session-wide mute state for inline feed playback. */
    private val _isMuted = MutableStateFlow(true)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    private val lock = Any()
    private val freePlayers = ArrayDeque<ExoPlayer>(POOL_SIZE)

    /**
     * Returns a player bound to [videoUrl], prepared and paused (feed videos
     * never autoplay; tapping opens the fullscreen player).
     */
    fun acquire(context: Context, videoUrl: String): ExoPlayer = synchronized(lock) {
        val player = freePlayers.removeFirstOrNull() ?: createPlayer(context)

        player.apply {
            stop()
            clearMediaItems()
            val mediaSource = ProgressiveMediaSource.Factory(
                VideoCache.getCacheDataSourceFactory(context)
            ).createMediaSource(MediaItem.fromUri(videoUrl))
            setMediaSource(mediaSource)
            prepare()
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = false
            volume = if (_isMuted.value) 0f else 1f
        }
    }

    /**
     * Hands a player back to the pool. It is stopped and detached from its media
     * (freeing decoder memory) but NOT destroyed, so the next [acquire] is cheap.
     * If the free list is already full the player is destroyed for real.
     */
    fun release(player: ExoPlayer) = synchronized(lock) {
        player.playWhenReady = false
        if (freePlayers.size < POOL_SIZE) {
            player.stop()
            player.clearMediaItems()
            freePlayers.addLast(player)
        } else {
            player.release()
        }
    }

    /** Destroys every pooled player (process teardown / logout). */
    fun releaseAll() = synchronized(lock) {
        while (freePlayers.isNotEmpty()) {
            freePlayers.removeFirst().release()
        }
    }

    private fun createPlayer(context: Context): ExoPlayer {
        // App context: the pool outlives activities, so never hold an Activity reference.
        return ExoPlayerHelper.createExoPlayer(context.applicationContext)
    }
}
