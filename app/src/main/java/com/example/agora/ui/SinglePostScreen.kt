package com.example.agora.ui

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.agora.model.Comment
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinglePostScreen(
    postId: String?,
    commentId: String? = null,
    feedViewModel: FeedViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    onBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit
) {
    val posts by feedViewModel.posts.collectAsState()
    val comments by feedViewModel.comments.collectAsState()
    val post = remember(posts, postId) { posts.find { it.id == postId } }

    var expandedImageUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(postId) {
        if (postId != null) {
            feedViewModel.fetchCommentsForPost(postId)
        }
    }

    // 🌟 Auto-scroll to specific comment when commentId argument is present
    LaunchedEffect(comments, commentId) {
        if (!commentId.isNullOrBlank() && comments.isNotEmpty()) {
            val commentIndex = comments.indexOfFirst { it.id == commentId }
            if (commentIndex != -1) {
                // Item 0 is PostCard, Item 1 is "Comments" header -> offset by 2
                listState.animateScrollToItem(commentIndex + 2)
            }
        }
    }

    // 🌟 REAL-TIME FROSTED GLASS SURFACE OVERLAY
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. CONTENT SCROLLING AREA
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            if (post == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = statusBarTop + 76.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Post not found or loading...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = statusBarTop + 76.dp,
                        bottom = navBarBottom + 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item {
                        PostCard(
                            post = post,
                            currentUserId = feedViewModel.currentUserId,
                            isExpanded = true,
                            onToggleExpand = {},
                            onLikeClicked = { isLiked, emoji -> feedViewModel.setLikeStatus(post.id, isLiked, emoji) },
                            onCommentClicked = {},
                            onImageClicked = { url -> expandedImageUrl = url },
                            onUserClicked = { onNavigateToProfile(post.userId) },
                            onOptionsClicked = {}
                        )
                    }

                    item {
                        Text(
                            text = "Comments",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }

                    if (comments.isEmpty()) {
                        item {
                            Text(
                                text = "No comments yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    } else {
                        items(comments, key = { comment -> comment.id }) { comment ->
                            val isTargetComment = comment.id == commentId
                            CommentItemRow(
                                comment = comment,
                                isTargetComment = isTargetComment
                            )
                        }
                    }
                }
            }

            // 2. FLOATING FAUX GLASS TOP BAR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(64.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Post",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    thickness = 1.dp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

// 🌟 COMMENT ITEM ROW WITH TEMPORARY 1-SECOND HIGHLIGHT ANIMATION
@Composable
fun CommentItemRow(
    comment: Comment,
    isTargetComment: Boolean,
    modifier: Modifier = Modifier
) {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
    val backgroundColor = remember { Animatable(Color.Transparent) }

    LaunchedEffect(isTargetComment) {
        if (isTargetComment) {
            backgroundColor.animateTo(
                targetValue = highlightColor,
                animationSpec = tween(durationMillis = 300)
            )
            delay(1000)
            backgroundColor.animateTo(
                targetValue = Color.Transparent,
                animationSpec = tween(durationMillis = 500)
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .background(backgroundColor.value, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (comment.userAvatarUrl != null) {
                AsyncImage(
                    model = comment.userAvatarUrl,
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = comment.displayName,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = comment.content,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
