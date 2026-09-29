package com.example.agora.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.agora.ui.theme.hazeChild
import coil.compose.AsyncImage
import com.example.agora.R
import com.example.agora.media.VideoPreloader
import com.example.agora.model.Post
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import com.example.agora.viewmodel.UploadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalFeedScreen(
    viewModel: FeedViewModel = viewModel(),
    themeViewModel: ThemeViewModel = viewModel(),
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToSearch: () -> Unit
) {
    // Collect the theme state directly from your custom toggle logic
    val isDarkTheme = com.example.agora.ui.theme.LocalDarkTheme.current
    val bgImage = if (isDarkTheme) R.drawable.app_background_dark else R.drawable.app_background_light

    val posts by viewModel.posts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val feedError by viewModel.feedError.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    // Hoisted Accordion Post Expansion State
    var expandedPostId by rememberSaveable { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val reportOptions = remember { listOf("Spam", "Harassment", "Hate Speech", "Misinformation", "Other") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // 🌟 Pre-cache the next 1-2 videos in the list so they start instantly when
    //    scrolled into view (or opened fullscreen). Re-triggered per scroll position.
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex, posts) {
        val upcomingVideos = posts
            .drop(firstVisibleIndex + 1)
            .take(2)
            .flatMap { post -> post.imageUrls.filter { isFeedVideoUrl(it) } }
        if (upcomingVideos.isNotEmpty()) {
            VideoPreloader.prefetch(context, upcomingVideos)
        }
    }

    // Automatically scroll to the top of the feed when a post is successfully created
    LaunchedEffect(uploadState) {
        if (uploadState is UploadState.Success) {
            coroutineScope.launch {
                delay(800.milliseconds) // Recomposition delay buffer so new item index 0 is rendered
                listState.animateScrollToItem(0)
            }
        }
    }

    // View States
    var expandedImageUrl by remember { mutableStateOf<String?>(null) }
    var showCommentSheet by remember { mutableStateOf(false) }
    var selectedPostId by remember { mutableStateOf<String?>(null) }
    var newCommentText by remember { mutableStateOf("") }

    // Options Menu & Delete Confirmation States
    var optionsPost by remember { mutableStateOf<Post?>(null) }
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    var postToEdit by remember { mutableStateOf<Post?>(null) }
    var editPostText by remember { mutableStateOf("") }

    var postToReport by remember { mutableStateOf<Post?>(null) }
    var reportReason by remember { mutableStateOf("Spam") }

    var keptUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var newlyAddedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        newlyAddedUris = newlyAddedUris + uris
    }

    val isCommentsSheetOpen = showCommentSheet && selectedPostId != null
    val feedBlurRadius by animateDpAsState(
        targetValue = if (isCommentsSheetOpen) 24.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "FeedCommentsBlur"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = if (isDarkTheme) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF070B14),
                            Color(0xFF0F172A),
                            Color(0xFF070B14)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF8FAFC),
                            Color(0xFFEEF2F6),
                            Color(0xFFE2E8F0)
                        )
                    )
                }
            )
    ) {

        // 1. THE AMBIENT MESH GRADIENT GLOW ORBS (Ultra-Smooth, Seamless iOS Feel)
        if (isDarkTheme) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-40).dp, y = (-20).dp)
                    .size(340.dp)
                    .blur(110.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4F46E5).copy(alpha = 0.28f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 60.dp, y = 100.dp)
                    .size(320.dp)
                    .blur(115.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF9333EA).copy(alpha = 0.24f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-30).dp, y = 80.dp)
                    .size(360.dp)
                    .blur(120.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF06B6D4).copy(alpha = 0.20f))
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(320.dp)
                    .blur(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF818CF8).copy(alpha = 0.25f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(320.dp)
                    .blur(95.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.20f))
            )
        }

        // 2. THE SCROLLING FEED
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshing = true
                    expandedPostId = null
                    viewModel.fetchPostsFromCloud()
                    isRefreshing = false
                }
            },
            // 🌟 Perf: only attach the full-feed GPU blur while the comments sheet is
            //    open/animating. A permanently-attached blur node re-rasterizes the
            //    whole scrollable every frame (major stutter source).
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (feedBlurRadius > 0.dp) Modifier.blur(radius = feedBlurRadius)
                    else Modifier
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = statusBarTop + 76.dp, // Clears top bar (64dp) + status bar inset + spacing
                    bottom = navBarBottom + 94.dp // Clears floating bottom bar (62dp + 16dp) + nav bar inset + buffer
                ),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                if (isLoading && posts.isEmpty()) {
                    items(3) {
                        PostCardShimmer()
                    }
                } else if (feedError != null && posts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = feedError ?: "Something went wrong",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.fetchPostsFromCloud() },
                                    shape = CircleShape
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                } else if (posts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No posts yet.",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Be the first to share something with the community!",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = posts,
                        key = { post -> post.id }
                    ) { post ->
                        val isPostExpanded = post.id == expandedPostId
                        val onToggle = remember(post.id) {
                            {
                                expandedPostId = if (expandedPostId == post.id) null else post.id
                            }
                        }
                        val onLike = remember(post.id) { { isLiked: Boolean, emoji: String -> viewModel.setLikeStatus(post.id, isLiked, emoji) } }
                        val onComment = remember(post.id) {
                            {
                                selectedPostId = post.id
                                viewModel.fetchCommentsForPost(post.id)
                                showCommentSheet = true
                            }
                        }
                        val onImage = remember { { url: String -> expandedImageUrl = url } }
                        val onUser = remember(post.userId) { { onNavigateToProfile(post.userId) } }
                        val onOptions = remember(post) { { optionsPost = post } }

                        PostCard(
                            post = post,
                            currentUserId = viewModel.currentUserId,
                            isExpanded = isPostExpanded,
                            onToggleExpand = onToggle,
                            onLikeClicked = onLike,
                            onCommentClicked = onComment,
                            onImageClicked = onImage,
                            onUserClicked = onUser,
                            onOptionsClicked = onOptions,
                            onShowReactorsClick = {
                                if (post.likes > 0) {
                                    viewModel.loadReactors(post.id)
                                }
                            }
                        )
                    }
                }
            }
        }

        // 3. FLOATING FROSTED GLASS TOP BAR (Seamless iOS Gradient Blur, zero hard borders)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hazeState != null) {
                        Modifier.hazeChild(state = hazeState, shape = RoundedCornerShape(0.dp))
                    } else {
                        Modifier
                    }
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (isDarkTheme) {
                            listOf(
                                Color(0xFF0F172A).copy(alpha = 0.88f),
                                Color(0xFF0F172A).copy(alpha = 0.60f),
                                Color(0xFF0F172A).copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.92f),
                                Color.White.copy(alpha = 0.70f),
                                Color.White.copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        }
                    )
                )
                .padding(bottom = 12.dp)
        ) {
            // Foreground Content
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(60.dp)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Invisible balance box for true visual center of title
                Box(modifier = Modifier.size(40.dp))

                Text(
                    text = "Agora",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Sleek circular glass button for search
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(
                        onClick = onNavigateToSearch,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Users",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // --- DYNAMIC POST OPTIONS BOTTOM SHEET ---
    if (optionsPost != null) {
        val targetPost = optionsPost!!
        ModalBottomSheet(onDismissRequest = { optionsPost = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, top = 8.dp)
            ) {
                if (targetPost.userId == viewModel.currentUserId) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            postToEdit = targetPost
                            editPostText = targetPost.content
                            keptUrls = targetPost.imageUrls
                            newlyAddedUris = emptyList()
                            optionsPost = null
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Edit Post", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                postToDelete = targetPost
                                optionsPost = null
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Delete Post", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            postToReport = targetPost
                            optionsPost = null
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Report", tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Report Post", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    // --- DELETE CONFIRMATION DIALOG ---
    if (postToDelete != null) {
        val targetDelete = postToDelete!!
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = { Text("Delete Post") },
            text = { Text("Are you sure you want to delete this post? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePost(targetDelete.id)
                        Toast.makeText(context, "Post deleted successfully", Toast.LENGTH_SHORT).show()
                        postToDelete = null
                    }
                ) {
                    Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // --- FULL SCREEN EDIT POST DIALOG ---
    if (postToEdit != null) {
        val targetEdit = postToEdit!!
        EditPostDialog(
            post = targetEdit,
            feedViewModel = viewModel,
            onDismiss = { postToEdit = null }
        )
    }

    // --- REPORT POST DIALOG ---
    if (postToReport != null) {
        val targetReport = postToReport!!
        AlertDialog(
            onDismissRequest = { postToReport = null },
            title = { Text("Report Post") },
            text = {
                Column {
                    Text("Why are you reporting this post?", modifier = Modifier.padding(bottom = 12.dp))
                    reportOptions.forEach { reason ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reportReason = reason }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = reportReason == reason,
                                onClick = { reportReason = reason },
                                colors = RadioButtonDefaults.colors(selectedColor = Color.Red)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(reason, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reportPost(targetReport.id, reportReason)
                        Toast.makeText(context, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show()
                        postToReport = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Submit Report", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { postToReport = null }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }

    // --- FULL SCREEN MEDIA VIEWER (IMAGE / VIDEO) ---
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

                    IconButton(
                        onClick = {
                            expandedImageUrl = null
                            scale = 1f
                            offset = Offset.Zero
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }

    // --- COMMENTS BOTTOM SHEET ---
    if (showCommentSheet && selectedPostId != null) {
        val currentPostId = selectedPostId!!
        CommentsBottomSheet(
            comments = comments,
            onPostComment = { text ->
                viewModel.addComment(currentPostId, text)
            },
            onDismissRequest = { showCommentSheet = false }
        )
    }

    // --- REACTORS BOTTOM SHEET ---
    val reactorsList by viewModel.reactorsList.collectAsState()
    val selectedPostIdForReactors = viewModel.selectedPostIdForReactors

    if (selectedPostIdForReactors != null) {
        ReactorsBottomSheet(
            reactorsList = reactorsList,
            onDismissRequest = { viewModel.selectedPostIdForReactors = null }
        )
    }
}

// 🌟 ZERO-CHROME MEDIA-FIRST POST CARD
@Composable
fun PostCard(
    post: Post,
    currentUserId: String?,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onLikeClicked: (isLiked: Boolean, emoji: String) -> Unit,
    onCommentClicked: () -> Unit,
    onImageClicked: (String) -> Unit,
    onUserClicked: () -> Unit,
    onOptionsClicked: () -> Unit,
    onShowReactorsClick: () -> Unit = {}
) {
    val context = LocalContext.current

    // Spring bounce scale animation for the Like button
    val likeScale by animateFloatAsState(
        targetValue = if (post.isLikedByMe) 1.25f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LikeBounceAnimation"
    )

    val gradientBorder = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer
        )
    )

    // 👉 1. WRAP THE ENTIRE CARD IN A ROOT BOX
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp)) // Clips the blur to the card shape
    ) {
        // 👉 3. THE FOREGROUND CONTENT
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                // 1. Diagonal Glass Tint Gradient
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), // Top-left: higher opacity for text readability
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) // Bottom-right: lower opacity for glass glow
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                // 2. Specular Rim Highlight (Catches light along the edge)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), // Crisp top-left highlight
                            Color.Transparent, // Fades away in the middle
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f) // Subtle bottom rim
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(16.dp)
        ) {
            // User Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onUserClicked() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .border(1.5.dp, gradientBorder, CircleShape)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (post.userAvatarUrl != null) {
                            AsyncImage(
                                model = post.userAvatarUrl,
                                contentDescription = "Avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Default Avatar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = post.firstName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "@${post.handle}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = post.timeAgo,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { onOptionsClicked() }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content with Smooth Content Size Animation on Expand
            Box(
                modifier = Modifier.animateContentSize(
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                ExpandablePostText(
                    text = post.content,
                    isExpanded = isExpanded,
                    onToggleExpand = onToggleExpand,
                    minimizedMaxLines = 3
                )
            }

            // Media (Multi-Media Carousel with Indicators)
            if (post.imageUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                PostMediaCarousel(
                    mediaUrls = post.imageUrls,
                    onMediaClick = { url -> onImageClicked(url) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tactile Micro-Interaction Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Glass pill grouping Like and Comment buttons
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        // 🌟 Instagram-Style Reaction Like Button
                        InstagramLikeButton(
                            isLiked = post.isLikedByMe,
                            initialEmoji = post.myReaction,
                            onLikeChanged = { isLiked, emoji ->
                                onLikeClicked(isLiked, emoji) // Triggers viewModel.setLikeStatus(post.id, isLiked, emoji)
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = post.likes.toString(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (post.isLikedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = post.likes > 0) {
                                onShowReactorsClick()
                            }
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Comment Button
                        IconButton(
                            onClick = { onCommentClicked() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "Comment",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = post.comments.toString(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Share Button Glass Pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    IconButton(
                        onClick = {
                            val shareUrl = "https://auth-agora.info/post/${post.id}"
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Hey check out this post I found on Agora:\n\n$shareUrl"
                                )
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Post"))
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// 🌟 PREMIUM SHIMMER SKELETON LOADING STATE
@Composable
fun PostCardShimmer() {
    val infiniteTransition = rememberInfiniteTransition(label = "ShimmerTransition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShimmerAlpha"
    )

    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.15f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(shimmerColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerColor)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerColor)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(shimmerColor)
        )
    }
}

// 🌟 Shared media-type sniffing for feed videos (matches PostMediaCarousel's check).
internal fun isFeedVideoUrl(url: String): Boolean =
    url.endsWith(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)
