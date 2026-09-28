package com.example.agora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

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
                    AsyncImage(
                        model = url,
                        contentDescription = "Post Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onMediaClick(url) },
                        contentScale = ContentScale.Crop
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
