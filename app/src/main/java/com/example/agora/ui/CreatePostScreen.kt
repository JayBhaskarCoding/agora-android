package com.example.agora.ui

import android.app.Activity
import android.content.ActivityNotFoundException
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
import androidx.compose.material.icons.filled.Movie
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.agora.media.CompressionState
import com.example.agora.media.NativePhotoEditor
import com.example.agora.media.VideoCompressorTrimmer
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.LocalDarkTheme
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel
import com.example.agora.viewmodel.UploadState
import kotlinx.coroutines.launch

@Composable
fun CreatePostDialog(
    feedViewModel: FeedViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    onDismiss: () -> Unit
) {
    // 🌟 Task 1: while a post is uploading, back-press / outside-tap must NOT
    // tear the composer down mid-flight — dismissal is locked until Supabase
    // confirms (success clears + closes via onSuccess) or the attempt fails.
    val isUploading by feedViewModel.isUploading.collectAsState()
    val guardedDismiss: () -> Unit = {
        if (!isUploading) onDismiss()
    }
    Dialog(
        onDismissRequest = guardedDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CreatePostScreen(
            feedViewModel = feedViewModel,
            themeViewModel = themeViewModel,
            onDismiss = guardedDismiss
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
    // The in-flight native photo editor handoff (the ACTION_EDIT session
    // between launch and result) — its result swaps in place, preserving
    // carousel order/count.
    var editSession by remember { mutableStateOf<NativePhotoEditor.Session?>(null) }

    var isCompressingVideo by remember { mutableStateOf(false) }
    var compressionProgress by remember { mutableFloatStateOf(0f) }

    // 🌟 Task 1: upload lock + error surface. Draft and media stay untouched
    // while Supabase works; a failure shows a Snackbar, never a blank screen.
    val isUploading by feedViewModel.isUploading.collectAsState()
    val uploadState by feedViewModel.uploadState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uploadState) {
        if (uploadState is UploadState.Error) {
            snackbarHostState.showSnackbar((uploadState as UploadState.Error).message)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val videoCompressorTrimmer = remember(context) { VideoCompressorTrimmer(context) }

    // 🌟 UX FIX: every pick lands DIRECTLY in the media carousel — images and
    // videos alike. No forced sequential edit loop; editing is opt-in via the
    // per-thumbnail card tap below, which opens the device's NATIVE photo
    // editor (ACTION_EDIT) — AI erasure, markup, filters, the full flagship
    // toolset instead of a custom in-app cropper.
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

    // 🌟 NATIVE photo editor handoff (replaces the custom Compose cropper):
    // a photo-card tap copies the pick into a writable cache file, exposes it
    // via FileProvider and fires ACTION_EDIT — Samsung Gallery / Google Photos
    // / whatever the device ships. On RESULT_OK the edited bytes are
    // snapshotted into a FRESH cache-file Uri (the same contract the upload
    // pipeline always consumed) and swapped into the carousel IN PLACE, so
    // the draft keeps its order, count and everything else untouched.
    val nativeEditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val session = editSession
        editSession = null
        if (session != null && result.resultCode == Activity.RESULT_OK) {
            coroutineScope.launch {
                val editedUri = NativePhotoEditor.finalizeEdit(context, session, result.data?.data)
                if (editedUri != null) {
                    selectedMedia = selectedMedia.map { if (it == session.sourceUri) editedUri else it }
                } else {
                    snackbarHostState.showSnackbar("Couldn't save the edited photo. Please try again.")
                }
            }
        }
    }

    val startNativePhotoEdit: (Uri) -> Unit = { source ->
        coroutineScope.launch {
            val session = NativePhotoEditor.prepare(context, source)
            if (session == null) {
                snackbarHostState.showSnackbar("Couldn't open that photo for editing.")
            } else {
                editSession = session
                try {
                    nativeEditLauncher.launch(NativePhotoEditor.buildIntent(session))
                } catch (e: ActivityNotFoundException) {
                    // No native editor installed — the draft stays exactly as it was.
                    editSession = null
                    snackbarHostState.showSnackbar("No photo editor found on this device.")
                }
            }
        }
    }

    // 🌟 Keyboard choreography: the caption field takes focus on open, and
    //    the scrollable content region keeps it fully visible above the IME.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val canPost = postText.trim().isNotBlank() || selectedMedia.isNotEmpty()

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
                        enabled = !isUploading,
                        onClick = {
                            postText = ""
                            selectedMedia = emptyList()
                            onDismiss()
                        }
                    ) {
                        Text(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            // 🌟 Task 3: belt-and-braces multi-click guard — the
                            // enabled flag blocks re-taps, but a double-fire before
                            // the first recomposition must not start two uploads.
                            if (isUploading) return@Button
                            val cleanText = postText.trim()
                            if (canPost) {
                                // 🌟 Task 1: fire the upload and KEEP everything on
                                // screen — state is cleared and the dialog dismissed
                                // ONLY from onSuccess, i.e. after the Supabase media
                                // upload AND the posts insert confirmed. A failure
                                // leaves the draft intact for an immediate retry.
                                feedViewModel.createPost(
                                    context = context,
                                    content = cleanText,
                                    mediaUris = selectedMedia,
                                    onSuccess = {
                                        postText = ""
                                        selectedMedia = emptyList()
                                        onDismiss()
                                    }
                                )
                            }
                        },
                        enabled = canPost && !isCompressingVideo && !isUploading,
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
                // 🌟 Task 2 layout: caption + carousel share ONE weighted
                //    region, so the Add Media bar sits flush under the content
                //    (and flush to the keyboard via the root imePadding). With
                //    media attached the block bottom-aligns — the carousel hugs
                //    the bar instead of stranding a slab of dead space below it;
                //    caption-only drafts stay top-aligned. The region still
                //    scrolls when the IME squeezes it.
                val regionScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .align(
                                if (selectedMedia.isNotEmpty()) Alignment.BottomCenter
                                else Alignment.TopCenter
                            )
                            .fillMaxWidth()
                            .verticalScroll(regionScroll)
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

                        // 🌟 Task 2: Media Preview Gallery (LazyRow with Click-to-Enlarge/Re-Trim)
                        if (selectedMedia.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 🌟 Task 2: wrap the content exactly — no fixed
                                    // height, no weight; the cards define the strip.
                                    .wrapContentHeight()
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
                                                // trim studio overlay, images open the device's NATIVE
                                                // photo editor (ACTION_EDIT).
                                                if (isVideo) activeEditUri = uri else startNativePhotoEdit(uri)
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
                                        // routes taps to the native photo editor; this just
                                        // hints at it.
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

        // 🌟 Task 1: full-screen upload lock — scrim + glass spinner pill above
        // the composer. Taps are swallowed so nothing can double-submit or wipe
        // the draft mid-flight; the dialog's own dismissal is guarded too.
        if (isUploading) {
            val uploadMessage =
                (uploadState as? UploadState.Uploading)?.message ?: "Posting…"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isDarkTheme) 0.55f else 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* swallow taps while uploading */ },
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
                            text = uploadMessage,
                            color = if (isDarkTheme) Color.White else Color(0xFF1C1C1E),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
                    color = if (isDarkTheme) Color(0xFF12141D).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.98f),
                    border = BorderStroke(1.dp, if (isDarkTheme) Color.White.copy(alpha = 0.09f) else Color(0xFFE3E4E9))
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

}
