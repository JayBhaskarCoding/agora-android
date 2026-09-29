package com.example.agora.ui

import android.widget.Toast
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.agora.model.Comment
import com.example.agora.model.Post
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.PostDetailUiState
import com.example.agora.viewmodel.PostDetailViewModel
import kotlinx.coroutines.delay

/**
 * Post details screen, opened from the feed or from a notification deep link
 * (`agora://post/{postId}` / `https://auth-agora.info/post/{postId}`).
 *
 * All data comes from the destination-scoped [PostDetailViewModel] (nav args are
 * read from its SavedStateHandle), so opening a post from a notification never
 * depends on — or mutates — the main feed's state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinglePostScreen(
    viewModel: PostDetailViewModel,
    onBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onPostChanged: (Post) -> Unit = {},
    /** Mutations are delegated to the feed's ViewModel by the host (MainScreen)
     *  so deletes/reports/edits keep the shared feed list in sync. Null = the
     *  corresponding sheet row is hidden. */
    onDeletePost: ((String) -> Unit)? = null,
    onReportPost: ((String, String) -> Unit)? = null,
    onEditPost: ((Post) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val commentId = viewModel.commentId
    val context = LocalContext.current
    val colors = rememberAgoraColors()

    var expandedImageUrl by remember { mutableStateOf<String?>(null) }

    // Options menu / delete / report states — mirrors FeedScreen's wiring so a
    // deep-linked post has identical interactive capabilities.
    var optionsPost by remember { mutableStateOf<Post?>(null) }
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    var postToReport by remember { mutableStateOf<Post?>(null) }
    var reportReason by remember { mutableStateOf("Spam") }
    val reportOptions = remember { listOf("Spam", "Harassment", "Hate Speech", "Misinformation", "Other") }

    // 🌟 COMMENT FETCH ON DIRECT NAVIGATION: when parachuting in from a
    //    notification deep link, the ViewModel's init-time fetch can complete
    //    BEFORE the post itself has loaded — and results arriving while the
    //    state is still Loading are dropped (comments only merge into Loaded).
    //    Re-fetch keyed on the postId as soon as the post IS loaded so the
    //    comment list always populates.
    val postId = viewModel.postId
    val isPostLoaded = uiState is PostDetailUiState.Loaded
    LaunchedEffect(postId, isPostLoaded) {
        if (postId != null && isPostLoaded) {
            viewModel.fetchComments(postId)
        }
    }

    // 🌟 Auto-scroll to specific comment when commentId argument is present
    LaunchedEffect(uiState, commentId) {
        val loaded = uiState as? PostDetailUiState.Loaded ?: return@LaunchedEffect
        if (!commentId.isNullOrBlank() && loaded.comments.isNotEmpty()) {
            val commentIndex = loaded.comments.indexOfFirst { it.id == commentId }
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

            when (val state = uiState) {
                is PostDetailUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = statusBarTop + 76.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is PostDetailUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = statusBarTop + 76.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.retry() }, shape = CircleShape) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is PostDetailUiState.Loaded -> {
                    val post = state.post
                    val comments = state.comments

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
                                currentUserId = viewModel.currentUserId,
                                isExpanded = true,
                                onToggleExpand = {},
                                onLikeClicked = { isLiked, emoji ->
                                    val updated = viewModel.setLikeStatus(isLiked, emoji)
                                    if (updated != null) {
                                        onPostChanged(updated)
                                    }
                                },
                                onCommentClicked = {},
                                onImageClicked = { url -> expandedImageUrl = url },
                                onUserClicked = { onNavigateToProfile(post.userId) },
                                onOptionsClicked = { optionsPost = post }
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

    // --- OPTIONS BOTTOM SHEET (mirrors FeedScreen) ---
    if (optionsPost != null) {
        val targetPost = optionsPost!!
        ModalBottomSheet(
            onDismissRequest = { optionsPost = null },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = colors.cardSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.hairline) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, top = 4.dp)
            ) {
                if (targetPost.userId == viewModel.currentUserId) {
                    if (onEditPost != null) {
                        OptionsSheetRow(
                            label = "Edit Post",
                            icon = Icons.Default.Edit,
                            tint = colors.textPrimary,
                            onClick = {
                                onEditPost(targetPost)
                                optionsPost = null
                            }
                        )
                    }

                    if (onDeletePost != null) {
                        OptionsSheetRow(
                            label = "Delete Post",
                            icon = Icons.Default.Delete,
                            tint = colors.danger,
                            bold = true,
                            onClick = {
                                postToDelete = targetPost
                                optionsPost = null
                            }
                        )
                    }
                } else {
                    if (onReportPost != null) {
                        OptionsSheetRow(
                            label = "Report Post",
                            icon = Icons.Default.Warning,
                            tint = colors.textPrimary,
                            onClick = {
                                postToReport = targetPost
                                optionsPost = null
                            }
                        )
                    }
                }
            }
        }
    }

    // --- DELETE CONFIRMATION DIALOG (mirrors FeedScreen) ---
    if (postToDelete != null) {
        val targetDelete = postToDelete!!
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            shape = RoundedCornerShape(26.dp),
            containerColor = colors.cardSurface,
            title = { Text("Delete Post", fontWeight = FontWeight.Bold, color = colors.textPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete this post? This action cannot be undone.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePost?.invoke(targetDelete.id)
                        Toast.makeText(context, "Post deleted successfully", Toast.LENGTH_SHORT).show()
                        postToDelete = null
                        // The post no longer exists — pop the detail screen back
                        // to wherever it came from (feed or notification).
                        onBack()
                    }
                ) {
                    Text("Delete", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) {
                    Text("Cancel", color = colors.textTertiary)
                }
            }
        )
    }

    // --- REPORT POST DIALOG (mirrors FeedScreen) ---
    if (postToReport != null) {
        val targetReport = postToReport!!
        AlertDialog(
            onDismissRequest = { postToReport = null },
            shape = RoundedCornerShape(26.dp),
            containerColor = colors.cardSurface,
            title = { Text("Report Post", fontWeight = FontWeight.Bold, color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        "Why are you reporting this post?",
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    reportOptions.forEach { reason ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { reportReason = reason }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = reportReason == reason,
                                onClick = { reportReason = reason },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.danger)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(reason, fontSize = 15.sp, color = colors.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReportPost?.invoke(targetReport.id, reportReason)
                        Toast.makeText(context, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show()
                        postToReport = null
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.danger,
                        contentColor = Color.White
                    )
                ) {
                    Text("Submit Report", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { postToReport = null }) {
                    Text("Cancel", color = colors.textTertiary)
                }
            }
        )
    }

    // --- FULL SCREEN MEDIA VIEWER (IMAGE / VIDEO) — exact FeedScreen wiring ---
    if (expandedImageUrl != null) {
        val mediaUrl = expandedImageUrl!!
        val isVideo = mediaUrl.endsWith(".mp4", ignoreCase = true) || mediaUrl.contains("video_", ignoreCase = true)

        Dialog(
            onDismissRequest = {
                expandedImageUrl = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            if (isVideo) {
                ExpandedVideoScreen(
                    videoUrl = mediaUrl,
                    onNavigateBack = { expandedImageUrl = null }
                )
            } else {
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = "Expanded Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        val maxX = (size.width * (scale - 1)) / 2
                                        val maxY = (size.height * (scale - 1)) / 2
                                        offset = Offset(
                                            x = (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                            y = (offset.y + pan.y * scale).coerceIn(-maxY, maxY)
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Fit
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .clickable {
                                expandedImageUrl = null
                                scale = 1f
                                offset = Offset.Zero
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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
