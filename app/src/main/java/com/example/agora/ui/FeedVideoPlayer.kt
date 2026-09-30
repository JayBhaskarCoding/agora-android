package com.example.agora.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.agora.media.FeedPlayerPool

/**
 * Inline feed video cell.
 *
 * Players come from [FeedPlayerPool] (bounded reuse — no create/destroy churn
 * while scrolling), previews always play muted — audio lives in the fullscreen
 * player — and playback never autoplays here;
 * tapping opens the fullscreen player.
 */
@OptIn(UnstableApi::class)
@Composable
fun FeedVideoPlayer(
    videoUrl: String,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isMuted by FeedPlayerPool.isMuted.collectAsState()

    // Pooled player: acquired once per (composition, url), returned on dispose
    // or when the item is recycled for a different URL.
    val exoPlayer = remember(videoUrl) { FeedPlayerPool.acquire(context, videoUrl) }

    // Dynamic volume updates when the shared mute state toggles
    LaunchedEffect(isMuted, exoPlayer) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Lifecycle Observer: pause on ON_PAUSE. The pool owns the player instance,
    // so we never release() it here — only hand it back.
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.playWhenReady = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.playWhenReady = false
            FeedPlayerPool.release(exoPlayer)
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable { onVideoClick() },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    // ✦ Crop-to-fill: the video covers the whole cell exactly
                    //    like a ContentScale.Crop photo — center-cropped, zero
                    //    pillarbox bars. View-level property only; playback
                    //    lifecycle and pool ownership are untouched.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                // Rebind when the pooled player instance changes (item recycling).
                if (playerView.player !== exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // Centered "Play" icon overlay
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Open Fullscreen Video",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
