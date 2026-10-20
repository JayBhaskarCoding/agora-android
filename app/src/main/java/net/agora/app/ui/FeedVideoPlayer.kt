package net.agora.app.ui

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import net.agora.app.R
import net.agora.app.media.FeedPlayerPool

/**
 * Inline feed video cell.
 *
 * Players come from [FeedPlayerPool] (bounded reuse — no create/destroy churn
 * while scrolling), previews always play muted — audio lives in the fullscreen
 * player — and playback never autoplays here;
 * tapping opens the fullscreen player.
 *
 * [cornerRadius] must match the container's shape. The player clips ITSELF to a
 * rounded outline because the video is center-cropped with
 * [AspectRatioFrameLayout.RESIZE_MODE_ZOOM], which measures the video larger
 * than the cell by design (see `res/layout/view_inline_video_player.xml`).
 */
@OptIn(UnstableApi::class)
@Composable
fun FeedVideoPlayer(
    videoUrl: String,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // One outline instance for the life of this cell: the factory and every
    // update must hand media3 the same clipper, and it must not be re-created
    // on each recomposition.
    val clipOutline = remember(cornerRadius) { roundedOutline(cornerRadius) }

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
                // ⚠️ Inflated from XML on purpose: PlayerView has no public
                //    surface-type setter, and the default is SurfaceView, which
                //    cannot be clipped (see view_inline_video_player.xml).
                (LayoutInflater.from(ctx)
                    .inflate(R.layout.view_inline_video_player, null) as PlayerView).apply {
                    player = exoPlayer
                    useController = false
                    // ✦ Crop-to-fill: the video covers the whole cell exactly
                    //    like a ContentScale.Crop photo — center-cropped, zero
                    //    pillarbox bars. View-level property only; playback
                    //    lifecycle and pool ownership are untouched.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // ZOOM deliberately measures the video larger than this
                    // view (it center-crops), so this outline is what stops the
                    // extra height/width from bleeding past the cell.
                    clipToOutline = true
                    outlineProvider = clipOutline
                    // The outline is built from width/height, so refresh it
                    // whenever the cell is re-measured.
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidateOutline() }
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
                // Keep the clip outline in sync if the container's shape changes.
                playerView.outlineProvider = clipOutline
                playerView.invalidateOutline()
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

/**
 * Clips the player to the same rounded rectangle as the media plate.
 *
 * Necessary because RESIZE_MODE_ZOOM measures the video larger than the cell
 * on purpose (center-crop). A SurfaceView would ignore this outline entirely —
 * it is composited by SurfaceFlinger, not by this view — which is why the
 * player is inflated as a TextureView (see view_inline_video_player.xml).
 */
private fun roundedOutline(radius: Dp) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        // Resolve the radius per-draw so a density/config change can't desync it
        // from the Compose shape it mirrors.
        val radiusPx = radius.value * view.resources.displayMetrics.density
        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
}
