package com.example.agora.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

private const val TAG = "PostMedia"

@Composable
fun PostMediaCarousel(
    mediaUrls: List<String>,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (mediaUrls.isEmpty()) return

    val pagerState = rememberPagerState { mediaUrls.size }

    Box(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val url = mediaUrls[page]
            val isVideo = url.endsWith(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 340.dp)
                    .clip(RoundedCornerShape(22.dp))
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

        // 🌟 Pager Dot Indicators for Multi-Media
        if (mediaUrls.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(mediaUrls.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                            )
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
            .listener(onError = { _, throwable ->
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

@Composable
private fun PostMediaPlaceholder(text: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = text,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
