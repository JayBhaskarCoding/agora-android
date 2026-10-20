package net.agora.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import net.agora.app.media.CloudinaryUrlHelper

/**
 * Inline video cell in the feed and on the single-post screen.
 *
 * ── THUMBNAIL-FIRST, BY DESIGN ──────────────────────────────────────────────
 * This composable **never** creates an ExoPlayer and **never** transfers video
 * bytes. It renders Cloudinary's poster frame for the video — a still WebP of
 * ~20–40 KB — and a play affordance.
 *
 * That is the whole fix for the 85 MB cache: the previous implementation
 * acquired a pooled player and called `prepare()` as soon as a cell composed,
 * which pulls the MP4 container plus a decoder frame for every video that is
 * merely *visible*. Scrolling a feed of three clips was enough to download all
 * three in full, and a 200 MB `SimpleCache` ceiling happily kept them.
 *
 * Playback starts only when the user taps: [onVideoClick] opens the fullscreen
 * player, which builds its own ExoPlayer bound to the compressed stream URL and
 * releases it when the dialog closes. So a player exists exactly while someone
 * is watching, and never while a post is "just sitting in the feed".
 *
 * ⚠️ Do not reintroduce a player here. If in-place playback is ever wanted, gate
 * the player on an explicit tap + visibility and release it on scroll-off, and
 * keep the poster underneath as the placeholder.
 */
@Composable
fun FeedVideoPlayer(
    videoUrl: String,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Cloudinary poster (still image). Non-Cloudinary URLs pass through unchanged.
    val posterUrl = remember(videoUrl) { CloudinaryUrlHelper.videoPosterUrl(videoUrl) }

    val request = remember(context, posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            // No crossfade: a fade-in on a 30 KB image that lands in one frame
            // reads as a flicker while scrolling.
            .crossfade(false)
            .build()
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable { onVideoClick() },
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = "Video preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // A poster that fails must not leave a layout hole: the play button
            // below stays visible and the black plate stands in for the frame.
            loading = { Box(Modifier.fillMaxSize()) },
            error = { Box(Modifier.fillMaxSize()) }
        )

        // Crisp play affordance — the only thing that signals "video".
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play video",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
