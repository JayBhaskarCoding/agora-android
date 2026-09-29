package com.example.agora.ui

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.agora.model.Post

private const val TAG = "PostMedia"

@Composable
fun PostMediaCarousel(
    mediaUrls: List<String>,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    post: Post? = null,
    onLikeClicked: ((isLiked: Boolean, emoji: String) -> Unit)? = null,
    onCommentClicked: (() -> Unit)? = null,
    onUserClicked: (() -> Unit)? = null,
    onShowReactorsClick: (() -> Unit)? = null
) {
    if (mediaUrls.isEmpty()) return

    val pagerState = rememberPagerState { mediaUrls.size }
    val context = LocalContext.current

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
                    .heightIn(min = 280.dp, max = 460.dp)
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

                // 🌟 FROSTED GLASS VIDEO OVERLAYS (Only if post metadata is provided and isVideo)
                if (isVideo && post != null) {
                    // 1. Right-side Action Column (Profile Avatar, Like, Comment, Share)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // User Profile Avatar Glass Pill
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .size(42.dp)
                                .clickable { onUserClicked?.invoke() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (post.userAvatarUrl != null) {
                                    AsyncImage(
                                        model = post.userAvatarUrl,
                                        contentDescription = "User Avatar",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Profile",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // Like Reaction Glass Pill
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                InstagramLikeButton(
                                    isLiked = post.isLikedByMe,
                                    initialEmoji = post.myReaction,
                                    onLikeChanged = { isLiked, emoji ->
                                        onLikeClicked?.invoke(isLiked, emoji)
                                    }
                                )
                            }
                        }
                        if (post.likes > 0) {
                            Text(
                                text = post.likes.toString(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .offset(y = (-6).dp)
                                    .clickable { onShowReactorsClick?.invoke() }
                            )
                        }

                        // Comment Glass Pill
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onCommentClicked?.invoke() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = "Comment",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        if (post.comments > 0) {
                            Text(
                                text = post.comments.toString(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.offset(y = (-6).dp)
                            )
                        }

                        // Share Glass Pill
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    val shareUrl = "https://auth-agora.info/post/${post.id}"
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "Check out this video on Agora:\n\n$shareUrl")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Post"))
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // 2. Bottom-left User Info & Caption Glass Panel
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.50f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 14.dp, end = 72.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = "@${post.handle}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.clickable { onUserClicked?.invoke() }
                            )
                            if (post.content.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = post.content,
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.90f),
                                    maxLines = 2
                                )
                            }
                        }
                    }
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
                val throwable = result.throwable
                Log.e(
                    TAG,
                    "Post image failed to load: $url — ${throwable.javaClass.simpleName}: ${throwable.message}",
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
