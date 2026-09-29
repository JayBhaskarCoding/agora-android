package com.example.agora.ui

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.agora.ui.theme.rememberAgoraColors

private const val TAG = "PostMedia"

/**
 * ✦ GALLERY PLATE MEDIA CAROUSEL
 *
 * Media is presented as a framed "gallery plate" inside the post card: a
 * fixed, calm 360dp canvas with 22dp corners, a hairline border and a deep
 * backing plate so photos/videos sit in a consistent editorial frame instead
 * of floating at arbitrary heights.
 *
 * The underlying media components are deliberately untouched:
 *  - videos still render through [FeedVideoPlayer] (pooled ExoPlayer),
 *  - images still load through [PostMediaImage] (SubcomposeAsyncImage with
 *    explicit loading/error slots and Coil error logging).
 */
@Composable
fun PostMediaCarousel(
    mediaUrls: List<String>,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (mediaUrls.isEmpty()) return

    val colors = rememberAgoraColors()
    val pagerState = rememberPagerState { mediaUrls.size }
    val plateShape = RoundedCornerShape(22.dp)

    Box(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val url = mediaUrls[page]
            val isVideo = url.endsWith(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(plateShape)
                    .background(colors.mediaPlate)
                    .border(width = 1.dp, color = colors.cardBorder, shape = plateShape)
            ) {
                if (isVideo) {
                    FeedVideoPlayer(
                        videoUrl = url,
                        onVideoClick = { onMediaClick(url) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PostMediaImage(
                        url = url,
                        onClick = { onMediaClick(url) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // ✦ Morphing pill indicators — the active page stretches into an
        //    accent-tinted pill with a spring, the rest stay quiet dots.
        if (mediaUrls.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(colors.scrim, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(mediaUrls.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 18.dp else 6.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "PagerDotWidth"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.42f),
                        animationSpec = tween(durationMillis = 220),
                        label = "PagerDotColor"
                    )
                    Box(
                        modifier = Modifier
                            .width(dotWidth)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }
    }
}

/**
 * A post image with an explicit failure state.
 *
 * `AsyncImage` with no error slot renders *nothing* when the fetch fails, which is
 * indistinguishable from a post that has no media — the exact symptom reported when a post
 * was opened on a device whose network cannot reach the media host. `SubcomposeAsyncImage`
 * lets the failure be shown and logged (URL + reason) instead of swallowed.
 */
@Composable
private fun PostMediaImage(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .listener(onError = { _, result ->
                // Coil 2.x hands the callback a Result wrapper, not the Throwable itself.
                val throwable = result.throwable
                Log.e(
                    TAG,
                    "Post image failed to load: $url — " +
                        "${throwable.javaClass.simpleName}: ${throwable.message}",
                    throwable
                )
            })
            .build()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = "Post Image",
        modifier = modifier.clickable { onClick() },
        contentScale = ContentScale.Crop,
        loading = { PostMediaPlaceholder(text = null) },
        error = { PostMediaPlaceholder(text = "Media unavailable") }
    )
}

/**
 * Restyled Noir placeholder: a soft diagonal wash on the media plate with a
 * quiet, wide-tracked failure caption. The loading slot intentionally renders
 * just the wash (no spinner) so photo reveals stay calm while scrolling.
 */
@Composable
private fun PostMediaPlaceholder(text: String?) {
    val colors = rememberAgoraColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(colors.mediaPlate, colors.insetSurface)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = colors.textTertiary
                )
            }
        }
    }
}
