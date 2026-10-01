package com.example.agora.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.agora.media.VideoPreloader
import com.example.agora.model.Post
import com.example.agora.ui.theme.AgoraRingGradient
import com.example.agora.ui.theme.AgoraType
import com.example.agora.ui.theme.hazeChild
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import com.example.agora.viewmodel.UploadState
import com.example.agora.ui.components.AgoraPrimaryButton
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * ✦ AGORA NOIR — HOME FEED
 *
 * The feed is rebuilt as an editorial-luxury experience:
 *  - a deep obsidian canvas with ambient aurora washes (warm paper in light mode),
 *  - a scroll-reactive frosted top bar with a gradient wordmark,
 *  - solid sculpted post cards with hairline borders, a top specular sheen,
 *    gallery-plate media and a divided action bar,
 *  - spring-driven micro-interactions (item reveal, morphing like counts).
 *
 * All data behavior is untouched: FeedViewModel collection, VideoPreloader
 * prefetching, pull-to-refresh, comment/reactor sheets, edit/delete/report
 * flows and the full-screen media viewer work exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalFeedScreen(
    viewModel: FeedViewModel = viewModel(),
    themeViewModel: ThemeViewModel = viewModel(),
    hazeState: HazeState? = null,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onCreatePost: () -> Unit = {}
) {
    val colors = rememberAgoraColors()

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

    // ✦ Scroll-reactive top bar: the frosted header materializes only once
    //    content actually slides underneath it.
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8
        }
    }

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

    // Automatically scroll to the top of the feed when a post is successfully created.
    // 🌟 Jump fix: skip the animation when the top item is ALREADY the first
    // visible one. The old unconditional animateScrollToItem(0) fired 800ms
    // after success — exactly when users tap their first like on the new post —
    // and yanked the list by its residual scroll offset, reading as a glitch.
    LaunchedEffect(uploadState) {
        if (uploadState is UploadState.Success) {
            coroutineScope.launch {
                delay(800.milliseconds) // Recomposition delay buffer so new item index 0 is rendered
                if (listState.firstVisibleItemIndex > 0) {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    // View States
    var expandedImageUrl by remember { mutableStateOf<String?>(null) }
    var showCommentSheet by remember { mutableStateOf(false) }
    var selectedPostId by remember { mutableStateOf<String?>(null) }

    // Options Menu & Delete Confirmation States
    var optionsPost by remember { mutableStateOf<Post?>(null) }
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    var postToEdit by remember { mutableStateOf<Post?>(null) }

    var postToReport by remember { mutableStateOf<Post?>(null) }
    var reportReason by remember { mutableStateOf("Spam") }

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
                brush = Brush.verticalGradient(
                    colors = listOf(colors.canvasTop, colors.canvasBottom)
                )
            )
    ) {

        // ✦ 1. AMBIENT AURORA WASHES — soft radial glows painted with gradient
        //    brushes (no runtime blur modifier), so they cost a single raster
        //    pass instead of re-blurring every frame.
        if (colors.isDark) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-100).dp, y = (-80).dp)
                    .size(440.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.auroraPrimary.copy(alpha = 0.20f),
                                0.55f to colors.auroraPrimary.copy(alpha = 0.08f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 110.dp, y = 80.dp)
                    .size(400.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.auroraSecondary.copy(alpha = 0.14f),
                                0.55f to colors.auroraSecondary.copy(alpha = 0.05f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-80).dp, y = 60.dp)
                    .size(420.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.auroraTertiary.copy(alpha = 0.10f),
                                0.55f to colors.auroraTertiary.copy(alpha = 0.04f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-90).dp, y = (-70).dp)
                    .size(400.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.auroraPrimary.copy(alpha = 0.34f),
                                0.6f to colors.auroraPrimary.copy(alpha = 0.12f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 90.dp, y = 40.dp)
                    .size(400.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.auroraSecondary.copy(alpha = 0.26f),
                                0.6f to colors.auroraSecondary.copy(alpha = 0.10f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
        }

        // ✦ 2. THE SCROLLING FEED
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
                    top = statusBarTop + 84.dp,   // Clears frosted top bar (64dp + divider + fade)
                    bottom = navBarBottom + 116.dp // Clears floating dock (60dp pill + 24dp float) + buffer
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (isLoading && posts.isEmpty()) {
                    items(3) {
                        PostCardShimmer()
                    }
                } else if (feedError != null && posts.isEmpty()) {
                    item {
                        FeedErrorState(
                            message = feedError ?: "Something went wrong",
                            onRetry = { viewModel.fetchPostsFromCloud() }
                        )
                    }
                } else if (posts.isEmpty()) {
                    item {
                        FeedEmptyState(onCreatePost = onCreatePost)
                    }
                } else {
                    items(
                        items = posts,
                        key = { post -> post.id },
                        contentType = { "post" }
                    ) { post ->
                        // ✦ One-shot reveal: cards rise and fade in the first time
                        //    they are composed (rememberSaveable keeps them still on
                        //    scroll-back and rotation).
                        var appeared by rememberSaveable(post.id) { mutableStateOf(false) }
                        LaunchedEffect(post.id) { appeared = true }
                        val enterAlpha by animateFloatAsState(
                            targetValue = if (appeared) 1f else 0f,
                            animationSpec = tween(durationMillis = 420),
                            label = "FeedItemAlpha"
                        )
                        val enterShift by animateFloatAsState(
                            targetValue = if (appeared) 0f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "FeedItemShift"
                        )

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
                            modifier = Modifier
                                .animateItem()
                                .graphicsLayer {
                                    alpha = enterAlpha
                                    translationY = enterShift * 40.dp.toPx()
                                },
                            isExpanded = isPostExpanded,
                            onToggleExpand = onToggle,
                            onLikeClicked = onLike,
                            onCommentClicked = onComment,
                            onImageClicked = onImage,
                            onUserClicked = onUser,
                            onOptionsClicked = onOptions,
                            onShowReactorsClick = {
                                // Always open — even at 0 the sheet shows its
                                // "no reactions yet" empty state.
                                viewModel.loadReactors(post.id)
                            }
                        )
                    }
                }
            }
        }

        // ✦ 3. SCROLL-REACTIVE FROSTED TOP BAR
        FeedTopBar(
            hazeState = hazeState,
            isScrolled = isScrolled,
            onSearchClick = onNavigateToSearch
        )
    }

    // --- DYNAMIC POST OPTIONS BOTTOM SHEET ---
    // 🌟 The SAME shared PostOptionsSheet on every screen that shows posts —
    // identical rows, order, styling and dismiss semantics, so "Edit Post"
    // behaves exactly the same here as it does on the home feed.
    optionsPost?.let { targetPost ->
        PostOptionsSheet(
            isOwner = targetPost.userId == viewModel.currentUserId,
            onEdit = {
                postToEdit = targetPost
                optionsPost = null
            },
            onDelete = {
                postToDelete = targetPost
                optionsPost = null
            },
            onReport = {
                postToReport = targetPost
                optionsPost = null
            },
            onDismiss = { optionsPost = null }
        )
    }

    // --- DELETE CONFIRMATION DIALOG ---
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
                        viewModel.deletePost(targetDelete.id)
                        Toast.makeText(context, "Post deleted successfully", Toast.LENGTH_SHORT).show()
                        postToDelete = null
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
                AgoraPrimaryButton(
                    onClick = {
                        viewModel.reportPost(targetReport.id, reportReason)
                        Toast.makeText(context, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show()
                        postToReport = null
                    }
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

    // --- FULL SCREEN MEDIA VIEWER (IMAGE / VIDEO) ---
    // 🌟 Shared component — the identical viewer on the feed, profile and
    // edit-post screens (pinch-zoom images, ExpandedVideoScreen for videos).
    expandedImageUrl?.let { mediaUrl ->
        FullscreenMediaViewerDialog(
            mediaUrl = mediaUrl,
            onDismiss = { expandedImageUrl = null }
        )
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
    val reactorsLoading by viewModel.reactorsLoading.collectAsState()
    val reactorsError by viewModel.reactorsError.collectAsState()
    val selectedPostIdForReactors = viewModel.selectedPostIdForReactors

    if (selectedPostIdForReactors != null) {
        ReactorsBottomSheet(
            reactorsList = reactorsList,
            isLoading = reactorsLoading,
            errorMessage = reactorsError,
            onRetry = { selectedPostIdForReactors?.let { viewModel.loadReactors(it) } },
            onDismissRequest = { viewModel.selectedPostIdForReactors = null },
            onUserClick = { userId ->
                // Dismiss the sheet first, then jump to the reactor's profile.
                viewModel.selectedPostIdForReactors = null
                onNavigateToProfile(userId)
            }
        )
    }
}

/**
 * ✦ SCROLL-REACTIVE FROSTED TOP BAR
 *
 * At rest it is pure air — just the gradient wordmark and a hairline search
 * chip floating on the canvas. The moment the feed scrolls underneath, a
 * frosted haze + scrim materializes with a rounded-bottom silhouette and a
 * hairline edge, then dissolves again on scroll-to-top.
 */
@Composable
private fun FeedTopBar(
    hazeState: HazeState?,
    isScrolled: Boolean,
    onSearchClick: () -> Unit
) {
    val colors = rememberAgoraColors()
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "TopBarScrimAlpha"
    )
    val barShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

    // 🌟 Two-zone header, zero hard edges:
    //    Zone 1 (blur/frost) covers ONLY the wordmark row and its gradient
    //    stays highly opaque (≥0.82α) all the way down — the haze clip
    //    boundary is therefore buried under solid scrim and can never read
    //    as a horizontal line.
    //    Zone 2 (feather) is a 60dp gradient-only spacer with explicit stops:
    //    it holds near the seam, drops steeply, then eases into full
    //    transparency so the header melts into the feed.
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 🌟 Perf: attach the haze blur only while the scrim is materialized.
                    if (hazeState != null && scrimAlpha > 0.02f) {
                        Modifier.hazeChild(state = hazeState, shape = barShape, blurRadius = 26.dp)
                    } else {
                        Modifier.clip(barShape)
                    }
                )
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            // Top 60% stays solid/opaque — no early, muddy fade.
                            0.0f to colors.canvasTop.copy(alpha = 0.94f * scrimAlpha),
                            0.6f to colors.canvasTop.copy(alpha = 0.90f * scrimAlpha),
                            1.0f to colors.canvasTop.copy(alpha = 0.82f * scrimAlpha)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ✦ Gradient wordmark with an accent period — the only branding the
                //    feed needs.
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        colors.textPrimary,
                                        colors.textPrimary.copy(alpha = 0.66f)
                                    )
                                ),
                                fontWeight = FontWeight.Black
                            )
                        ) {
                            append("agora")
                        }
                        withStyle(
                            SpanStyle(
                                color = colors.accent,
                                fontWeight = FontWeight.Black
                            )
                        ) {
                            append(".")
                        }
                    },
                    style = AgoraType.Wordmark
                )

                Spacer(modifier = Modifier.weight(1f))

                // Hairline search chip
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.insetSurface.copy(alpha = 0.75f))
                        .border(width = 1.dp, color = colors.hairline, shape = CircleShape)
                        .clickable(onClick = onSearchClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search Users",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }


        // ✦ Feather zone — gradient only: no blur, no clip, nothing to cut a
        //    hard edge. 60dp of physical space (was 12dp) lets the scrim fall
        //    off completely: holds 0.82α at the blur seam, drops steeply to
        //    0.32α by 45%, then eases smoothly to air at the bottom.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to colors.canvasTop.copy(alpha = 0.82f * scrimAlpha),
                            0.45f to colors.canvasTop.copy(alpha = 0.32f * scrimAlpha),
                            1.0f to Color.Transparent
                        )
                    )
                )
        )
    }
}

/** A single row inside the post-options sheet (Edit / Delete / Report). */
@Composable
internal fun OptionsSheetRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 16.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

/**
 * 🌟 The ONE post-options sheet, shared by the home feed and profile screens.
 * Owner: Edit Post + Delete Post. Everyone else: Report Post. Rows use the
 * shared OptionsSheetRow so styling can never drift between screens again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostOptionsSheet(
    isOwner: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = rememberAgoraColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.cardSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.hairline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, top = 4.dp)
        ) {
            if (isOwner) {
                OptionsSheetRow(
                    label = "Edit Post",
                    icon = Icons.Default.Edit,
                    tint = colors.textPrimary,
                    onClick = onEdit
                )

                OptionsSheetRow(
                    label = "Delete Post",
                    icon = Icons.Default.Delete,
                    tint = colors.danger,
                    bold = true,
                    onClick = onDelete
                )
            } else {
                OptionsSheetRow(
                    label = "Report Post",
                    icon = Icons.Default.Warning,
                    tint = colors.textPrimary,
                    onClick = onReport
                )
            }
        }
    }
}

/**
 * ✦ NOIR POST CARD
 *
 * A solid sculpted surface (never translucent) with:
 *  - a 28dp silhouette, hairline border, light-mode elevation and a faint
 *    specular sheen across the top edge,
 *  - an identity header: gradient ring reserved for the viewer's own posts,
 *    tight name/handle stack, chip-style overflow button,
 *  - caption typography driven through LocalTextStyle/LocalContentColor so
 *    [ExpandablePostText] keeps its expand/collapse logic untouched,
 *  - the gallery-plate [PostMediaCarousel],
 *  - an action bar separated by a hairline: reaction button + counts on the
 *    left, share on the right.
 *
 * Media plumbing (ExoPlayer pool, SubcomposeAsyncImage + error logging) is
 * entirely delegated and unchanged.
 */
@Composable
fun PostCard(
    post: Post,
    currentUserId: String?,
    modifier: Modifier = Modifier,
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
    val colors = rememberAgoraColors()
    val cardShape = RoundedCornerShape(28.dp)
    val isOwnPost = currentUserId != null && post.userId == currentUserId

    // Like count cross-fades into the accent the instant it's loved.
    val likeColor by animateColorAsState(
        targetValue = if (post.isLikedByMe) colors.accent else colors.textTertiary,
        animationSpec = tween(durationMillis = 220),
        label = "LikeCountColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = colors.cardElevation,
                    shape = cardShape,
                    // 🌟 Light mode: soft, diffused black hover shadow instead
                    //    of the dark mode's deeper slate cast.
                    spotColor = if (colors.isDark) Color(0xFF1F2430).copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.07f),
                    ambientColor = if (colors.isDark) Color(0xFF1F2430).copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f)
                )
                .clip(cardShape)
                .background(colors.cardSurface)
                // Specular sheen kissing the top edge of the card.
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to colors.cardSheen,
                            0.16f to Color.Transparent
                        )
                    )
                )
                .border(width = 1.dp, color = colors.cardBorder, shape = cardShape)
        ) {
            // ── Identity header ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🌟 Profile tap target: an inner Row wrapping ONLY the avatar and
                //    the name/@handle block. weight(1f) pushes the overflow chip to
                //    the far right as a completely independent click target — the
                //    ripple never spans the empty space or overlaps the "..." menu.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onUserClicked() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar — sweep-gradient ring only for the viewer's own posts.
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                brush = if (isOwnPost) {
                                    AgoraRingGradient
                                } else {
                                    Brush.linearGradient(
                                        listOf(colors.cardBorder, colors.cardBorder)
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.insetSurface),
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
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = post.firstName,
                            style = AgoraType.AuthorName,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "@${post.handle}",
                                style = AgoraType.Meta,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = "  ·  ${post.timeAgo}",
                                style = AgoraType.Meta,
                                color = colors.textTertiary
                            )
                        }
                    }
                }

                // Overflow chip
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.insetSurface.copy(alpha = 0.65f))
                        .clickable { onOptionsClicked() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = "Post options",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── Caption ──────────────────────────────────────────────────
            if (post.content.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .animateContentSize(
                            animationSpec = spring(stiffness = Spring.StiffnessLow)
                        )
                ) {
                    // Drive ExpandablePostText's typography without touching it:
                    // its Text composables inherit color + style from locals.
                    CompositionLocalProvider(
                        LocalContentColor provides colors.textPrimary,
                        LocalTextStyle provides AgoraType.Body
                    ) {
                        ExpandablePostText(
                            text = post.content,
                            isExpanded = isExpanded,
                            onToggleExpand = onToggleExpand,
                            minimizedMaxLines = 3
                        )
                    }
                }
            }

            // ── Gallery plate media ──────────────────────────────────────
            if (post.imageUrls.isNotEmpty()) {
                PostMediaCarousel(
                    mediaUrls = post.imageUrls,
                    onMediaClick = { url -> onImageClicked(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = if (post.content.isNotBlank()) 14.dp else 12.dp
                        )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Action bar ───────────────────────────────────────────────
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🌟 Instagram-Style Reaction Like Button (long-press tray intact)
                InstagramLikeButton(
                    isLiked = post.isLikedByMe,
                    initialEmoji = post.myReaction,
                    onLikeChanged = { isLiked, emoji ->
                        onLikeClicked(isLiked, emoji) // Triggers viewModel.setLikeStatus(post.id, isLiked, emoji)
                    }
                )

                Text(
                    text = post.likes.toString(),
                    style = AgoraType.Count,
                    color = likeColor,
                    modifier = Modifier
                        .clickable { onShowReactorsClick() }
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Comment cluster
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onCommentClicked() }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "Comments",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = post.comments.toString(),
                        style = AgoraType.Count,
                        color = colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Share
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            val shareUrl = "https://auth-agora.info/post/${post.id}"
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Hey check out this post I found on Agora:\n\n$shareUrl"
                                )
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Post"))
                        }
                        .padding(11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share post",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

// ✦ PREMIUM PULSING SKELETON — mirrors the real card anatomy 1:1 so the
//   feed never "jumps" when data lands.
@Composable
fun PostCardShimmer() {
    val colors = rememberAgoraColors()
    val infiniteTransition = rememberInfiniteTransition(label = "SkeletonPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SkeletonAlpha"
    )

    val block = if (colors.isDark) {
        Color.White.copy(alpha = alpha * 0.07f)
    } else {
        Color(0xFF1F2430).copy(alpha = alpha * 0.10f)
    }
    val cardShape = RoundedCornerShape(28.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(cardShape)
            .background(colors.cardSurface)
            .border(width = 1.dp, color = colors.cardBorder, shape = cardShape)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(block)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(block)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.28f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(block)
                )
            }
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(0.9f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(block)
        )
        Spacer(modifier = Modifier.height(9.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(0.55f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(block)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(block)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(block)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(block)
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(block)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ✦ EDITORIAL EMPTY STATE — invites the first post instead of dead-ending.
@Composable
private fun FeedEmptyState(onCreatePost: () -> Unit) {
    val colors = rememberAgoraColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, bottom = 40.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colors.accentSoft)
                .border(width = 1.dp, color = colors.accent.copy(alpha = 0.22f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "The forum awaits",
            fontSize = 21.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.4).sp,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No posts yet. Be the first to start a conversation with the community.",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            color = colors.textTertiary
        )
        Spacer(modifier = Modifier.height(26.dp))
        AgoraPrimaryButton(onClick = onCreatePost) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create the first post", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ✦ CALM FAILURE STATE — clear cause, one obvious action.
@Composable
private fun FeedErrorState(message: String, onRetry: () -> Unit) {
    val colors = rememberAgoraColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, bottom = 40.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(colors.danger.copy(alpha = 0.10f))
                .border(width = 1.dp, color = colors.danger.copy(alpha = 0.20f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = message,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(20.dp))
        AgoraPrimaryButton(onClick = onRetry) {
            Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// 🌟 Shared media-type sniffing for feed videos (matches PostMediaCarousel's check).
internal fun isFeedVideoUrl(url: String): Boolean =
    url.endsWith(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)



