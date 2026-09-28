package com.example.agora.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.agora.media.ExoPlayerHelper
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun VideoTrimmerDialog(
    videoUri: Uri,
    isCompressing: Boolean,
    compressionProgress: Float,
    onTrimConfirmed: (startMs: Long, endMs: Long) -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            VideoTrimmerContent(
                videoUri = videoUri,
                isCompressing = isCompressing,
                compressionProgress = compressionProgress,
                onTrimConfirmed = onTrimConfirmed,
                onCancel = onCancel
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoTrimmerContent(
    videoUri: Uri,
    isCompressing: Boolean,
    compressionProgress: Float,
    onTrimConfirmed: (startMs: Long, endMs: Long) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }

    // 🌟 Single ExoPlayer instance with Unisoc Codec Fallback bound directly to PlayerView
    val exoPlayer = remember(context, videoUri) {
        ExoPlayerHelper.createExoPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    var totalDurationMs by remember { mutableFloatStateOf(10000f) }
    var trimRange by remember { mutableStateOf(0f..10000f) }
    var currentPlayheadMs by remember { mutableFloatStateOf(0f) }

    // 🌟 DisposableEffect: Instantly stop and release audio/video when videoUri changes or dialog closes
    DisposableEffect(videoUri, exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val dur = exoPlayer.duration.toFloat().coerceAtLeast(1000f)
                    totalDurationMs = dur
                    if (trimRange.endInclusive > dur || trimRange.endInclusive == 10000f) {
                        trimRange = 0f..dur
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // 🌟 Bounded Playback Engine (Restricts playback strictly within trimStartMs..trimEndMs)
    LaunchedEffect(exoPlayer, trimRange, isPlaying) {
        while (isPlaying) {
            val currentPos = exoPlayer.currentPosition.toFloat()
            currentPlayheadMs = currentPos

            if (currentPos >= trimRange.endInclusive || currentPos < trimRange.start) {
                exoPlayer.seekTo(trimRange.start.toLong())
                currentPlayheadMs = trimRange.start
            }
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trim & Compress Video", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = onCancel, enabled = !isCompressing) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Single Video Player Box with Direct AndroidView Binding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = {
                    isPlaying = !isPlaying
                    exoPlayer.playWhenReady = isPlaying
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Toggle Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Timestamp Readouts
        val startSec = (trimRange.start / 1000).toInt()
        val endSec = (trimRange.endInclusive / 1000).toInt()
        val currentSec = (currentPlayheadMs / 1000).toInt()
        val trimDurationSec = (endSec - startSec).coerceAtLeast(0)

        Text(
            text = "Current: ${formatTime(currentSec)} / Trim: ${formatTime(startSec)} - ${formatTime(endSec)} (${trimDurationSec}s)",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Trim Range Boundaries", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        RangeSlider(
            value = trimRange,
            onValueChange = { range ->
                trimRange = range
                if (currentPlayheadMs < range.start || currentPlayheadMs > range.endInclusive) {
                    exoPlayer.seekTo(range.start.toLong())
                }
            },
            valueRange = 0f..totalDurationMs,
            enabled = !isCompressing,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text("Active Playhead Position", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Slider(
            value = currentPlayheadMs.coerceIn(trimRange.start, trimRange.endInclusive),
            onValueChange = { pos ->
                currentPlayheadMs = pos
                exoPlayer.seekTo(pos.toLong())
            },
            valueRange = trimRange.start..trimRange.endInclusive,
            enabled = !isCompressing,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isCompressing) {
            LinearProgressIndicator(
                progress = { compressionProgress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Trimming & Transcoding Video...", fontSize = 13.sp)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onTrimConfirmed(trimRange.start.toLong(), trimRange.endInclusive.toLong()) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Trim")
                }
            }
        }
    }
}

private fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
