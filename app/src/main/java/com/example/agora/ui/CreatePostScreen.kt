package com.example.agora.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.agora.media.CompressionState
import com.example.agora.media.VideoCompressorTrimmer
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import kotlinx.coroutines.launch

@Composable
fun CreatePostDialog(
    feedViewModel: FeedViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CreatePostScreen(
            feedViewModel = feedViewModel,
            themeViewModel = themeViewModel,
            onDismiss = onDismiss
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun CreatePostScreen(
    feedViewModel: FeedViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = LocalDarkTheme.current
    val agora = rememberAgoraColors()

    var postText by remember { mutableStateOf("") }
    
    // 🌟 Task 1: Multi-Selection List State & Active Edit URI State
    var selectedMedia by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var activeEditUri by remember { mutableStateOf<Uri?>(null) }
    // The carousel image currently inside the Compose-native crop studio —
    // its result swaps in place, preserving carousel order/count.
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }

    var isCompressingVideo by remember { mutableStateOf(false) }
    var compressionProgress by remember { mutableFloatStateOf(0f) }

    val coroutineScope = rememberCoroutineScope()
    val videoCompressorTrimmer = remember(context) { VideoCompressorTrimmer(context) }

    // 🌟 UX FIX: every pick lands DIRECTLY in the media carousel — images and
    // videos alike. No forced sequential crop loop; editing is opt-in via the
    // per-thumbnail Crop/Edit chip below, which opens AgoraImageCropDialog —
    // a Compose-native studio (pinch/pan fluid, dark glass chrome) replacing
    // the legacy uCrop activity hop entirely.
    val multiMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newMediaList = selectedMedia.toMutableList()
            for (uri in uris) {
                if (!newMediaList.contains(uri)) {
                    newMediaList.add(uri)
                }
            }
            selectedMedia = newMediaList
        }
    }

    val canPost = postText.trim().isNotBlank() || selectedMedia.isNotEmpty()

    // ✦ Agora Noir canvas — no blurred wallpaper, the same aurora gradient as the feed.
    VibrantGlassBackground(isDarkTheme = isDarkTheme) {

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(64.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        postText = ""
                        selectedMedia = emptyList()
                        onDismiss()
                    }) {
                        Text(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            val cleanText = postText.trim()
                            if (canPost) {
                                // 🌟 Pass full selectedMedia list directly to ViewModel without dropping videos via firstOrNull()
                                feedViewModel.createPost(
                                    context = context,
                                    content = cleanText,
                                    mediaUris = selectedMedia,
                                    onSuccess = onDismiss
                                )
                                postText = ""
                                selectedMedia = emptyList()
                            }
                        },
                        enabled = canPost && !isCompressingVideo,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        Text("Post", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                TextField(
                    value = postText,
                    onValueChange = { postText = it },
                    placeholder = {
                        Text(
                            text = "What's on your mind?",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )

                // 🌟 Task 2: Media Preview Gallery (LazyRow with Click-to-Enlarge/Re-Trim)
                if (selectedMedia.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(selectedMedia, key = { uri -> uri.toString() }) { uri ->
                            val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true || uri.toString().contains(".mp4")

                            Box(
                                modifier = Modifier
                                    // 🌟 Feed-sized cards: 80% of the carousel width at a
                                    // 4:5 portrait ratio — exactly how media reads in the feed.
                                    .fillParentMaxWidth(0.8f)
                                    .aspectRatio(4f / 5f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.dp, agora.cardBorder, RoundedCornerShape(20.dp))
                                    .clickable {
                                        // 🌟 The whole card is the edit button: videos open the
                                        // trim studio overlay, images open the crop studio directly.
                                        if (isVideo) activeEditUri = uri else cropSourceUri = uri
                                    }
                            ) {
                                if (isVideo) {
                                    VideoThumbnail(
                                        videoUri = uri,
                                        modifier = Modifier.fillMaxSize()
                                    )
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
                                } else {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Selected Media Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                IconButton(
                                    onClick = { selectedMedia = selectedMedia - uri },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                        .size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove Media",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                // 🌟 Decorative edit badge — the entire card already
                                // routes taps to the crop studio; this just hints at it.
                                if (!isVideo) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.55f))
                                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Crop,
                                            contentDescription = "Crop / Edit",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = agora.cardSurface,
                    border = BorderStroke(1.dp, agora.cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                multiMediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Add Media",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Add Media",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }

                        if (selectedMedia.isNotEmpty()) {
                            Text(
                                text = "${selectedMedia.size} selected",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 🌟 Task 3: Enlarged Player & Re-Trim Overlay
    if (activeEditUri != null) {
        val editingUri = activeEditUri!!
        val isVideo = context.contentResolver.getType(editingUri)?.startsWith("video") == true || editingUri.toString().contains(".mp4")

        Dialog(
            onDismissRequest = { activeEditUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            if (isVideo) {
                Surface(
                    modifier = Modifier
                        // 🌟 Immersive sheet — bounded height so the player
                        // preview can weight-expand above the trim deck.
                        .fillMaxWidth(0.96f)
                        .fillMaxHeight(0.92f),
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF12141D).copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
                ) {
                    VideoTrimmerContent(
                        videoUri = editingUri,
                        isCompressing = isCompressingVideo,
                        compressionProgress = compressionProgress,
                        onTrimConfirmed = { startMs, endMs ->
                            coroutineScope.launch {
                                isCompressingVideo = true
                                videoCompressorTrimmer.trimAndCompressVideo(editingUri, startMs, endMs)
                                    .collect { state ->
                                        when (state) {
                                            is CompressionState.Compressing -> {
                                                compressionProgress = state.progress
                                            }
                                            is CompressionState.Success -> {
                                                val trimmedUri = state.outputUri
                                                // 🌟 Replace old activeEditUri in selectedMedia list with new trimmed URI
                                                selectedMedia = selectedMedia.map { if (it == editingUri) trimmedUri else it }
                                                isCompressingVideo = false
                                                activeEditUri = null
                                            }
                                            is CompressionState.Error -> {
                                                isCompressingVideo = false
                                                activeEditUri = null
                                            }
                                            is CompressionState.Idle -> {}
                                        }
                                    }
                            }
                        },
                        onCancel = { activeEditUri = null }
                    )
                }
            } else {
                // Enlarged Photo Viewer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = editingUri,
                        contentDescription = "Enlarged Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    IconButton(
                        onClick = { activeEditUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }

    // ✦ Compose-native crop studio — pinch/pan fluid, dark glass chrome. The
    //    result is a cache-file Uri, exactly the contract the upload pipeline
    //    already consumed from uCrop, so nothing downstream changes.
    cropSourceUri?.let { sourceUri ->
        AgoraImageCropDialog(
            sourceUri = sourceUri,
            onDismiss = { cropSourceUri = null },
            onCropped = { croppedUri ->
                // Swap in place: carousel position/order/count preserved.
                selectedMedia = selectedMedia.map { if (it == sourceUri) croppedUri else it }
                cropSourceUri = null
            }
        )
    }
}
