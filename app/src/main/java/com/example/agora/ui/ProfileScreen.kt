package com.example.agora.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.agora.R
import com.example.agora.data.supabaseClient
import com.example.agora.model.Post
import com.example.agora.model.Profile
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from

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
    // Collect the theme state for dynamic background image
    val isDarkTheme = com.example.agora.ui.theme.LocalDarkTheme.current
    val bgImage = if (isDarkTheme) R.drawable.app_background_dark else R.drawable.app_background_light
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
    var newCommentText by remember { mutableStateOf("") }
    var expandedImageUrl by remember { mutableStateOf<String?>(null) }

    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        newlyAddedUris = newlyAddedUris + uris
    }

    val comments by feedViewModel.comments.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

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

    val gradientBorder = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer
        )
    )

    // --- DYNAMIC POST OPTIONS BOTTOM SHEET ---
    if (optionsPost != null) {
        val targetPost = optionsPost!!
        ModalBottomSheet(onDismissRequest = { optionsPost = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 32.dp, top = 8.dp)) {
                if (targetPost.userId == currentLoggedInUserId) {
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
                        modifier = Modifier.fillMaxWidth().clickable {
                            postToDelete = targetPost
                            optionsPost = null
                        }.padding(16.dp),
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
                TextButton(onClick = {
                    feedViewModel.deletePost(targetDelete.id)
                    Toast.makeText(context, "Post deleted", Toast.LENGTH_SHORT).show()
                    postToDelete = null
                }) { Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }

    // --- FULL SCREEN EDIT POST DIALOG ---
    if (postToEdit != null) {
        val targetEdit = postToEdit!!
        Dialog(onDismissRequest = { postToEdit = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { postToEdit = null }) { Text("Cancel", color = Color.Gray, fontSize = 16.sp) }

                        Button(
                            onClick = {
                                feedViewModel.editPost(context, targetEdit.id, editPostText, keptUrls, newlyAddedUris)
                                Toast.makeText(context, "Updating post...", Toast.LENGTH_SHORT).show()
                                postToEdit = null
                            },
                            enabled = editPostText.isNotBlank()
                        ) { Text("Save") }
                    }

                    TextField(
                        value = editPostText,
                        onValueChange = { editPostText = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                    )

                    if (keptUrls.isNotEmpty() || newlyAddedUris.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(keptUrls, key = { url -> url }) { url ->
                                Box(modifier = Modifier.size(120.dp)) {
                                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                    IconButton(
                                        onClick = { keptUrls = keptUrls - url },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).size(24.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                            }
                            items(newlyAddedUris, key = { uri -> uri.toString() }) { uri ->
                                Box(modifier = Modifier.size(120.dp)) {
                                    AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                    IconButton(
                                        onClick = { newlyAddedUris = newlyAddedUris - uri },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).size(24.dp)
                                    ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                        TextButton(onClick = { editLauncher.launch("image/*") }) { Text("📷 Add Photos") }
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
            title = { Text("Report Post") },
            text = {
                Column {
                    Text("Why are you reporting this post?", modifier = Modifier.padding(bottom = 12.dp))
                    reportOptions.forEach { reason ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { reportReason = reason }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = reportReason == reason, onClick = { reportReason = reason }, colors = RadioButtonDefaults.colors(selectedColor = Color.Red))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(reason, fontSize = 16.sp)
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Submit Report", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { postToReport = null }) { Text("Cancel", color = Color.Gray) } }
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

                    IconButton(
                        onClick = {
                            expandedImageUrl = null
                            scale = 1f
                            offset = Offset.Zero
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
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

    Box(modifier = Modifier.fillMaxSize()) {

        // 1. THE BACKGROUND IMAGE WITH BLUR
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = bgImage),
            contentDescription = "Profile Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.blur(radius = 7.dp).fillMaxSize()
        )

        // 2. THE SCROLLING PROFILE CONTENT
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = statusBarTop + 76.dp, // Offsets header below floating top bar at rest
                bottom = 96.dp
            )
        ) {
                // 🌟 HERO HEADER COMPONENT (Edit Profile Button Removed)
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
                            // Avatar with Gradient Ring Border
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .border(2.dp, gradientBorder, CircleShape)
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(enabled = avatarUrl != null) { expandedImageUrl = avatarUrl },
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
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(24.dp))

                            // Metrics Row
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = userPosts.size.toString(),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "Posts",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = userPosts.sumOf { it.likes }.toString(),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "Likes",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = allMediaUrls.size.toString(),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "Media",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Display Name & Muted Handle
                        Text(
                            text = profileFullName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = handle,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 🌟 TAB ROW (POSTS vs MEDIA)
                item {
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { selectedTabIndex = 0 },
                            text = { Text("Posts", fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.ViewAgenda, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = { selectedTabIndex = 1 },
                            text = { Text("Media", fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.GridView, contentDescription = null) }
                        )
                    }
                }

                // 🌟 TAB CONTENT SWITCHER
                if (selectedTabIndex == 0) {
                    // POSTS TAB
                    if (userPosts.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No posts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Spacer(modifier = Modifier.height(12.dp))
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
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No media posts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        item {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                allMediaUrls.chunked(3).forEach { rowUrls ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowUrls.forEach { url ->
                                            val isVideo = url.contains(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)

                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable {
                                                        expandedImageUrl = url
                                                    }
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

        // 3. FLOATING MAXIMIZED FAUX GLASS TOP BANNER
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 1.0f),
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isMyProfile || userId != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    Text(
                        text = handle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (isMyProfile) {
                    IconButton(onClick = { onOpenDrawer() }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Glass Bottom Border Edge
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                thickness = 1.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
