package com.example.agora.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.agora.data.supabaseClient
import com.example.agora.model.Post
import com.example.agora.model.Profile
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.AgoraRingGradient
import com.example.agora.ui.theme.AgoraType
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from

/**
 * ✦ AGORA NOIR — PROFILE
 *
 * Restyled to match the flagship feed: obsidian aurora canvas (no blurred
 * wallpaper), a hero header with the signature sweep-gradient ring on your own
 * profile, a stat plate with hairline separators, a segmented pill tab
 * switcher and gallery-style media tiles.
 *
 * All behavior is untouched: profile fetch, post options / edit / delete /
 * report flows, media viewer, comment sheet and tab state work exactly as
 * before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    feedViewModel: FeedViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    userId: String? = null,
    onBack: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val colors = rememberAgoraColors()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentLoggedInUserId = supabaseClient.auth.currentUserOrNull()?.id
    val targetUserId = userId ?: currentLoggedInUserId
    val isMyProfile = targetUserId == currentLoggedInUserId

    val allPosts by feedViewModel.posts.collectAsState()
    val userPosts = remember(allPosts, targetUserId) {
        allPosts.filter { it.userId == targetUserId }
    }

    val allMediaUrls = remember(userPosts) {
        userPosts.flatMap { it.imageUrls }
    }

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var expandedPostId by rememberSaveable { mutableStateOf<String?>(null) }

    val reportOptions = remember { listOf("Spam", "Harassment", "Hate Speech", "Misinformation", "Other") }

    var profileFullName by remember { mutableStateOf("Loading...") }
    var handle by remember { mutableStateOf("loading") }
    var avatarUrl by remember { mutableStateOf<String?>(null) }

    var optionsPost by remember { mutableStateOf<Post?>(null) }
    var postToDelete by remember { mutableStateOf<Post?>(null) }

    var postToEdit by remember { mutableStateOf<Post?>(null) }
    var editPostText by remember { mutableStateOf("") }

    var postToReport by remember { mutableStateOf<Post?>(null) }
    var reportReason by remember { mutableStateOf("Spam") }

    var keptUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var newlyAddedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var showCommentSheet by remember { mutableStateOf(false) }
    var selectedPostId by remember { mutableStateOf<String?>(null) }
    var expandedImageUrl by remember { mutableStateOf<String?>(null) }

    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        newlyAddedUris = newlyAddedUris + uris
    }

    val comments by feedViewModel.comments.collectAsState()

    LaunchedEffect(targetUserId) {
        if (targetUserId != null) {
            try {
                val profile = supabaseClient.from("profiles").select { filter { eq("id", targetUserId) } }.decodeSingle<Profile>()
                val formattedName = listOfNotNull(profile.firstName, profile.lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                profileFullName = if (formattedName.isNotBlank()) formattedName else "User"
                handle = "@${profile.handle}"
                avatarUrl = profile.avatarUrl
            } catch (e: Exception) {
                profileFullName = "User"
                handle = "@user"
            }
        }
    }

    // --- DYNAMIC POST OPTIONS BOTTOM SHEET ---
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
                if (targetPost.userId == currentLoggedInUserId) {
                    OptionsSheetRow(
                        label = "Edit Post",
                        icon = Icons.Default.Edit,
                        tint = colors.textPrimary,
                        onClick = {
                            postToEdit = targetPost
                            editPostText = targetPost.content
                            keptUrls = targetPost.imageUrls
                            newlyAddedUris = emptyList()
                            optionsPost = null
                        }
                    )

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
                } else {
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
                TextButton(onClick = {
                    feedViewModel.deletePost(targetDelete.id)
                    Toast.makeText(context, "Post deleted", Toast.LENGTH_SHORT).show()
                    postToDelete = null
                }) { Text("Delete", color = colors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) { Text("Cancel", color = colors.textTertiary) }
            }
        )
    }

    // --- FULL SCREEN EDIT POST DIALOG ---
    if (postToEdit != null) {
        val targetEdit = postToEdit!!
        Dialog(onDismissRequest = { postToEdit = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.canvasTop) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { postToEdit = null }) {
                            Text("Cancel", color = colors.textTertiary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                feedViewModel.editPost(context, targetEdit.id, editPostText, keptUrls, newlyAddedUris)
                                Toast.makeText(context, "Updating post...", Toast.LENGTH_SHORT).show()
                                postToEdit = null
                            },
                            enabled = editPostText.isNotBlank(),
                            shape = CircleShape
                        ) { Text("Save", fontWeight = FontWeight.Bold) }
                    }

                    TextField(
                        value = editPostText,
                        onValueChange = { editPostText = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = colors.accent
                        )
                    )

                    if (keptUrls.isNotEmpty() || newlyAddedUris.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(keptUrls, key = { url -> url }) { url ->
                                Box(modifier = Modifier.size(120.dp)) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(16.dp))
                                            .border(width = 1.dp, color = colors.cardBorder, shape = RoundedCornerShape(16.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    IconButton(
                                        onClick = { keptUrls = keptUrls - url },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).size(24.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                            }
                            items(newlyAddedUris, key = { uri -> uri.toString() }) { uri ->
                                Box(modifier = Modifier.size(120.dp)) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(16.dp))
                                            .border(width = 1.dp, color = colors.cardBorder, shape = RoundedCornerShape(16.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    IconButton(
                                        onClick = { newlyAddedUris = newlyAddedUris - uri },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).size(24.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.cardSurface)
                            .padding(8.dp)
                    ) {
                        TextButton(onClick = { editLauncher.launch("image/*") }) {
                            Text("📷 Add Photos", color = colors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
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
                Button(
                    onClick = {
                        feedViewModel.reportPost(targetReport.id, reportReason)
                        Toast.makeText(context, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show()
                        postToReport = null
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.danger,
                        contentColor = Color.White
                    )
                ) { Text("Submit Report", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { postToReport = null }) { Text("Cancel", color = colors.textTertiary) } }
        )
    }

    // --- FULL SCREEN MEDIA VIEWER (IMAGE / VIDEO) ---
    if (expandedImageUrl != null) {
        val mediaUrl = expandedImageUrl!!
        val isVideo = mediaUrl.contains(".mp4", ignoreCase = true) || mediaUrl.contains("video_", ignoreCase = true)

        Dialog(
            onDismissRequest = { expandedImageUrl = null },
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
                    modifier = Modifier.fillMaxSize().background(Color.Black),
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
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
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
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
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
                feedViewModel.addComment(currentPostId, text)
            },
            onDismissRequest = { showCommentSheet = false }
        )
    }

    // ✦ THE PROFILE CANVAS
    VibrantGlassBackground(isDarkTheme = colors.isDark) {

        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = statusBarTop + 76.dp, // Offsets header below floating top bar at rest
                bottom = navBarBottom + 108.dp // Clears the floating nav pill
            )
        ) {
            // ✦ HERO HEADER — ring-signed avatar + stat plate
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar — sweep-gradient ring on your own profile, hairline ring on others
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = if (isMyProfile) {
                                        AgoraRingGradient
                                    } else {
                                        Brush.linearGradient(listOf(colors.cardBorder, colors.cardBorder))
                                    }
                                )
                                .clickable(enabled = avatarUrl != null) { expandedImageUrl = avatarUrl },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(CircleShape)
                                    .background(colors.insetSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarUrl != null) {
                                    AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = "Profile Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Default Avatar",
                                        modifier = Modifier.size(44.dp),
                                        tint = colors.textTertiary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(20.dp))

                        // Stat plate with hairline separators
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(22.dp))
                                .background(colors.cardSurface)
                                .border(width = 1.dp, color = colors.cardBorder, shape = RoundedCornerShape(22.dp))
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileStat(
                                value = userPosts.size.toString(),
                                label = "POSTS",
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                modifier = Modifier.height(30.dp),
                                thickness = 1.dp,
                                color = colors.hairline
                            )
                            ProfileStat(
                                value = userPosts.sumOf { it.likes }.toString(),
                                label = "LIKES",
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                modifier = Modifier.height(30.dp),
                                thickness = 1.dp,
                                color = colors.hairline
                            )
                            ProfileStat(
                                value = allMediaUrls.size.toString(),
                                label = "MEDIA",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = profileFullName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = handle,
                        style = AgoraType.Meta,
                        color = colors.textTertiary
                    )
                }
            }

            // ✦ SEGMENTED PILL TABS (Posts vs Media)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clip(CircleShape)
                        .background(colors.cardSurface)
                        .border(width = 1.dp, color = colors.cardBorder, shape = CircleShape)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileTabSegment(
                        selected = selectedTabIndex == 0,
                        icon = Icons.Default.ViewAgenda,
                        label = "Posts",
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTabIndex = 0 }
                    )
                    ProfileTabSegment(
                        selected = selectedTabIndex == 1,
                        icon = Icons.Default.GridView,
                        label = "Media",
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTabIndex = 1 }
                    )
                }
            }

            // ✦ TAB CONTENT SWITCHER
            if (selectedTabIndex == 0) {
                // POSTS TAB
                if (userPosts.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No posts yet.",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isMyProfile) {
                                    "Share something with the community."
                                } else {
                                    "When they post, it will show up here."
                                },
                                fontSize = 13.5.sp,
                                color = colors.textTertiary
                            )
                        }
                    }
                } else {
                    items(
                        items = userPosts,
                        key = { post -> post.id }
                    ) { post ->
                        val isPostExpanded = post.id == expandedPostId
                        val onToggle = remember(post.id) {
                            { expandedPostId = if (expandedPostId == post.id) null else post.id }
                        }
                        val onLike = remember(post.id) { { isLiked: Boolean, emoji: String -> feedViewModel.setLikeStatus(post.id, isLiked, emoji) } }
                        val onComment = remember(post.id) {
                            {
                                selectedPostId = post.id
                                feedViewModel.fetchCommentsForPost(post.id)
                                showCommentSheet = true
                            }
                        }
                        val onImage = remember { { url: String -> expandedImageUrl = url } }
                        val onOptions = remember(post) { { optionsPost = post } }

                        PostCard(
                            post = post,
                            currentUserId = currentLoggedInUserId,
                            isExpanded = isPostExpanded,
                            onToggleExpand = onToggle,
                            onLikeClicked = onLike,
                            onCommentClicked = onComment,
                            onImageClicked = onImage,
                            onUserClicked = { },
                            onOptionsClicked = onOptions
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                // MEDIA GRID TAB
                if (allMediaUrls.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.GridView,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = colors.textTertiary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("No media posts.", color = colors.textTertiary, fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            allMediaUrls.chunked(3).forEach { rowUrls ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowUrls.forEach { url ->
                                        val isVideo = url.contains(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(colors.mediaPlate)
                                                .clickable {
                                                    expandedImageUrl = url
                                                }
                                                .border(width = 1.dp, color = colors.cardBorder, shape = RoundedCornerShape(16.dp))
                                        ) {
                                            AsyncImage(
                                                model = url,
                                                contentDescription = "Media Grid Item",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )

                                            if (isVideo) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.Center)
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(Color.Black.copy(alpha = 0.6f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play Video",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    repeat(3 - rowUrls.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ✦ FLOATING CANVAS TOP BANNER — seamless scrim, no hard divider
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to colors.canvasTop.copy(alpha = 0.94f),
                            0.68f to colors.canvasTop.copy(alpha = 0.86f),
                            1.0f to Color.Transparent
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isMyProfile || userId != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.textPrimary
                            )
                        }
                    }
                    Text(
                        text = handle,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        letterSpacing = (-0.3).sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isMyProfile) {
                    // Hairline menu chip (opens the Noir drawer)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(colors.insetSurface.copy(alpha = 0.75f))
                            .border(width = 1.dp, color = colors.hairline, shape = CircleShape)
                            .clickable { onOpenDrawer() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

/** One stat inside the hero plate: heavy count + wide-tracked micro label. */
@Composable
private fun ProfileStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = rememberAgoraColors()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.3).sp,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = AgoraType.MicroLabel,
            color = colors.textTertiary
        )
    }
}

/** A segment inside the pill tab switcher — springs between accent and quiet. */
@Composable
private fun ProfileTabSegment(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = rememberAgoraColors()
    val segmentBg by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else Color.Transparent,
        animationSpec = tween(durationMillis = 240),
        label = "TabSegmentBg"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textTertiary,
        animationSpec = tween(durationMillis = 240),
        label = "TabSegmentTint"
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(segmentBg)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}
