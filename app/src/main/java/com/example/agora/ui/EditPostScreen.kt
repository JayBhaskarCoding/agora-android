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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.example.agora.media.CompressionState
import com.example.agora.media.VideoCompressorTrimmer
import com.example.agora.model.Post
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.EditMediaItem
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.UploadState
import com.example.agora.ui.components.AgoraPrimaryButton
import kotlinx.coroutines.launch

@Composable
fun EditPostDialog(
    post: Post,
    feedViewModel: FeedViewModel,
    onDismiss: () -> Unit
) {
    // 🌟 Task 2: while the update is in flight, back-press / outside-tap must
    // NOT tear the editor down — dismissal is locked until Supabase confirms
    // (success closes via onSuccess) or the attempt fails.
    val isUpdating by feedViewModel.isUpdating.collectAsState()
    val guardedDismiss: () -> Unit = {
        if (!isUpdating) onDismiss()
    }
    Dialog(
        onDismissRequest = guardedDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        EditPostScreen(
            post = post,
            feedViewModel = feedViewModel,
            onDismiss = guardedDismiss
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun EditPostScreen(
    post: Post,
    feedViewModel: FeedViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = LocalDarkTheme.current
    val agora = rememberAgoraColors()

    var editPostText by remember { mutableStateOf(post.content) }

    // 🌟 Task 1: Initialize currentMediaItems with existing remote media_urls
    var currentMediaItems by remember {
        mutableStateOf(
            post.imageUrls.map { url ->
                val isVid = url.endsWith(".mp4", ignoreCase = true) || url.contains("video_", ignoreCase = true)
                EditMediaItem(remoteUrl = url, isVideo = isVid)
            }
        )
    }

    var activeEditUri by remember { mutableStateOf<Uri?>(null) }
    var isCompressingVideo by remember { mutableStateOf(false) }
    var compressionProgress by remember { mutableFloatStateOf(0f) }

    // 🌟 Task 2: update lock + error surface — parity with CreatePostScreen.
    // The editor stays fully intact while Supabase works; a failure shows a
    // Snackbar and dismissal happens only after a confirmed UPDATE.
    val isUpdating by feedViewModel.isUpdating.collectAsState()
    val uploadState by feedViewModel.uploadState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uploadState) {
        if (uploadState is UploadState.Error) {
            snackbarHostState.showSnackbar((uploadState as UploadState.Error).message)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val videoCompressorTrimmer = remember(context) { VideoCompressorTrimmer(context) }

    // The item currently inside the Compose-native crop studio — its result
    // swaps into the media list in place.
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }

    // The EXISTING remote media currently in the fullscreen viewer — the same
    // component the feed opens (pinch-zoom image / ExpandedVideoScreen player).
    var expandedRemoteUrl by remember { mutableStateOf<String?>(null) }

    // 🌟 Task 2: Multi-Media Picker Launcher for appending new items
    val multiMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newItems = currentMediaItems.toMutableList()
            for (uri in uris) {
                val mimeType = context.contentResolver.getType(uri)
                val isVid = mimeType?.startsWith("video") == true || uri.toString().contains(".mp4")
                newItems.add(EditMediaItem(localUri = uri, isVideo = isVid))
            }
            currentMediaItems = newItems
        }
    }

    // 🌟 Keyboard choreography: the caption field takes focus on open, and
    //    the scrollable content region keeps it fully visible above the IME.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val canSave = editPostText.trim().isNotBlank() || currentMediaItems.isNotEmpty()

    // ✦ Agora Noir canvas — no blurred wallpaper, the same aurora gradient as the feed.
    VibrantGlassBackground(isDarkTheme = isDarkTheme) {

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    TextButton(
                        enabled = !isUpdating,
                        onClick = onDismiss
                    ) {
                        Text(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    AgoraPrimaryButton(
                        onClick = {
                            // 🌟 Task 3: multi-click guard — an aggressive double-tap
                            // must not fire two UPDATE pipelines / duplicate uploads.
                            if (isUpdating) return@AgoraPrimaryButton
                            if (canSave) {
                                feedViewModel.savePostChanges(
                                    context = context,
                                    postId = post.id,
                                    updatedCaption = editPostText.trim(),
                                    currentItems = currentMediaItems,
                                    onSuccess = onDismiss
                                )
                            }
                        },
                        enabled = canSave && !isCompressingVideo && !isUpdating
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                // 🌟 Task 2 layout (parity with CreatePostScreen): caption +
                //    carousel share ONE weighted region, so the Add Media bar
                //    sits flush under the content (and flush to the keyboard via
                //    the root imePadding). With media attached the block bottom-
                //    aligns — the carousel hugs the bar instead of stranding a
                //    slab of dead space below it; caption-only drafts stay top-
                //    aligned. The region still scrolls when the IME squeezes it.
                val regionScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .align(
                                if (currentMediaItems.isNotEmpty()) Alignment.BottomCenter
                                else Alignment.TopCenter
                            )
                            .fillMaxWidth()
                            .verticalScroll(regionScroll)
                    ) {
                        TextField(
                            value = editPostText,
                            onValueChange = { editPostText = it },
                            placeholder = {
                                Text(
                                    text = "Edit your post...",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                                .focusRequester(focusRequester)
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

                        // 🌟 Task 2: Mixed Media Preview Gallery
                        if (currentMediaItems.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 🌟 Task 2: wrap the content exactly — no fixed
                                    // height, no weight; the cards define the strip.
                                    .wrapContentHeight()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(currentMediaItems, key = { item -> item.id }) { item ->
                                    Box(
                                        modifier = Modifier
                                            // 🌟 Feed-sized cards: 80% of the carousel width
                                            // at a 4:5 portrait ratio — exact parity with
                                            // CreatePostScreen's media previews.
                                            .fillParentMaxWidth(0.8f)
                                            .aspectRatio(4f / 5f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .border(1.dp, agora.cardBorder, RoundedCornerShape(20.dp))
                                            .clickable {
                                                val local = item.localUri
                                                if (local != null) {
                                                    // NEW local media → edit routing:
                                                    // videos open the trim studio, photos
                                                    // re-crop in the Compose-native studio.
                                                    if (item.isVideo) {
                                                        activeEditUri = local
                                                    } else {
                                                        cropSourceUri = local
                                                    }
                                                } else if (item.remoteUrl != null) {
                                                    // EXISTING remote media → view-only:
                                                    // the same fullscreen viewer the feed
                                                    // opens (image zoom / video player).
                                                    // There is no local source to crop/trim.
                                                    expandedRemoteUrl = item.remoteUrl
                                                }
                                            }
                                    ) {
                                        val isVidItem = item.isVideo || item.remoteUrl?.contains(".mp4", ignoreCase = true) == true

                                        if (item.localUri != null && isVidItem) {
                                            VideoThumbnail(videoUri = item.localUri, modifier = Modifier.fillMaxSize())
                                        } else {
                                            AsyncImage(
                                                model = item.displayModel,
                                                contentDescription = "Media Preview",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }

                                        if (isVidItem) {
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

                                        IconButton(
                                            onClick = { currentMediaItems = currentMediaItems - item },
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

                                        // 🌟 Decorative edit badge — parity with CreatePostScreen:
                                        //    hints that tapping a local photo re-opens the crop
                                        //    studio (remote-only media has no local source to edit).
                                        if (!isVidItem && item.localUri != null) {
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

                        if (currentMediaItems.isNotEmpty()) {
                            Text(
                                text = "${currentMediaItems.size} items",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 🌟 Task 2: full-screen update lock — scrim + glass spinner pill above
        // the editor. Taps are swallowed so nothing can double-submit or wipe
        // the draft mid-flight; the dialog's own dismissal is guarded too.
        if (isUpdating) {
            val updateMessage =
                (uploadState as? UploadState.Uploading)?.message ?: "Saving changes…"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isDarkTheme) 0.55f else 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* swallow taps while updating */ },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.78f),
                    border = BorderStroke(
                        1.dp,
                        if (isDarkTheme) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.06f)
                    ),
                    shadowElevation = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 34.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(38.dp),
                            color = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = updateMessage,
                            color = if (isDarkTheme) Color.White else Color(0xFF1C1C1E),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // 🌟 Re-Trim Video Dialog
    if (activeEditUri != null) {
        val editingUri = activeEditUri!!
        val isVid = context.contentResolver.getType(editingUri)?.startsWith("video") == true || editingUri.toString().contains(".mp4")

        if (isVid) {
            VideoTrimmerDialog(
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
                                        currentMediaItems = currentMediaItems.map { item ->
                                            if (item.localUri == editingUri) item.copy(localUri = trimmedUri) else item
                                        }
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
    }

    // ✦ Compose-native crop studio — replaces the legacy uCrop activity hop
    //    for photo re-cropping. Cache-file Uri out, swapped into the item.
    cropSourceUri?.let { sourceUri ->
        AgoraImageCropDialog(
            sourceUri = sourceUri,
            onDismiss = { cropSourceUri = null },
            onCropped = { croppedUri ->
                currentMediaItems = currentMediaItems.map { item ->
                    if (item.localUri == sourceUri) item.copy(localUri = croppedUri) else item
                }
                cropSourceUri = null
            }
        )
    }

    // 🌟 Fullscreen viewer for EXISTING remote media — view-only by design;
    // the exact same component the home feed opens. Takes the plain remote
    // URL string; video/image routing happens inside via isFeedVideoUrl.
    expandedRemoteUrl?.let { mediaUrl ->
        FullscreenMediaViewerDialog(
            mediaUrl = mediaUrl,
            onDismiss = { expandedRemoteUrl = null }
        )
    }
}
