package com.example.agora.ui

import android.app.Activity
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
import androidx.compose.material.icons.filled.PlayArrow
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
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.model.AspectRatio
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun EditPostDialog(
    post: Post,
    feedViewModel: FeedViewModel,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        EditPostScreen(
            post = post,
            feedViewModel = feedViewModel,
            onDismiss = onDismiss
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

    val coroutineScope = rememberCoroutineScope()
    val videoCompressorTrimmer = remember(context) { VideoCompressorTrimmer(context) }

    // UCrop Launcher for Photo Cropping
    val uCropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val croppedUri = UCrop.getOutput(result.data!!)
            if (croppedUri != null && activeEditUri != null) {
                val targetUri = activeEditUri!!
                currentMediaItems = currentMediaItems.map { item ->
                    if (item.localUri == targetUri) item.copy(localUri = croppedUri) else item
                }
                activeEditUri = null
            }
        }
    }

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

    val canSave = editPostText.trim().isNotBlank() || currentMediaItems.isNotEmpty()

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
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
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
                        enabled = canSave && !isCompressingVideo,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
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
                TextField(
                    value = editPostText,
                    onValueChange = { editPostText = it },
                    placeholder = { Text("Edit your post...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )

                // 🌟 Task 2: Mixed Media Preview Gallery
                if (currentMediaItems.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(currentMediaItems, key = { item -> item.id }) { item ->
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.dp, agora.cardBorder, RoundedCornerShape(20.dp))
                                    .clickable {
                                        if (item.localUri != null) {
                                            activeEditUri = item.localUri
                                            if (!item.isVideo) {
                                                // Launch UCrop for photo re-cropping
                                                val destinationUri = Uri.fromFile(File(context.cacheDir, "crop_${UUID.randomUUID()}.jpg"))
                                                val options = UCrop.Options().apply {
                                                    setFreeStyleCropEnabled(true)
                                                    setAspectRatioOptions(
                                                        0,
                                                        AspectRatio("Free", 0f, 0f),
                                                        AspectRatio("1:1", 1f, 1f),
                                                        AspectRatio("4:5", 4f, 5f),
                                                        AspectRatio("16:9", 16f, 9f)
                                                    )
                                                }
                                                val uCropIntent = UCrop.of(item.localUri, destinationUri)
                                                    .withOptions(options)
                                                    .getIntent(context)
                                                uCropLauncher.launch(uCropIntent)
                                            }
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
}
