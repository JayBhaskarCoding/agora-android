package com.example.agora.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/*
 * ✦ AGORA VIDEO STUDIO — premium Compose rebuild of the legacy trimmer.
 *
 *  - FILMSTRIP TIMELINE: MediaMetadataRetriever pulls evenly spaced frames on
 *    Dispatchers.IO (never on the main thread), shown as a horizontal strip so
 *    the user sees exactly what they are trimming.
 *  - TRIM HANDLES: grabbable start/end thumbs drawn over the filmstrip plus a
 *    slide-anywhere window drag; dragging seeks the ExoPlayer preview to the
 *    exact millisecond (scrub pauses playback, release resumes from the new
 *    trim start). Minimum trim 1s, inversion impossible (coerced bounds).
 *  - BOUNDED PLAYBACK: the preview loops inside the trim window.
 *  - NOIR GLASS CHROME: translucent #12141D toolbars with fade-seam hairlines
 *    and the brand accent on the active trim region/handles — same language
 *    as the image crop studio.
 *
 * The transcoding contract is untouched: onTrimConfirmed(startMs, endMs)
 * still feeds VideoCompressorTrimmer, and the upload pipeline consumes its
 * output exactly as before.
 */

private val TrimSurface = Color(0xFF12141D).copy(alpha = 0.96f)
private val TrimBarTop = Color(0xFF12141D).copy(alpha = 0.92f)
private val TrimBarBottom = Color(0xFF12141D).copy(alpha = 0.72f)
private val TrimHairline = Color.White.copy(alpha = 0.10f)
private val TrimAccent = Color(0xFF818CF8)
private val TrimTextPrimary = Color(0xFFF4F5FA)
private val TrimTextSecondary = Color(0xFFA9AEC0)
private val TrimCell = Color(0xFF1C1F2B)
private const val MIN_TRIM_MS = 1000f
private const val FILMSTRIP_CELLS = 10

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
            color = TrimSurface,
            border = BorderStroke(1.dp, TrimHairline)
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
    var durationMs by remember { mutableFloatStateOf(0f) }
    var trimStart by remember { mutableFloatStateOf(0f) }
    var trimEnd by remember { mutableFloatStateOf(0f) }
    var playheadMs by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    var frames by remember(videoUri) { mutableStateOf<List<ImageBitmap>>(emptyList()) }

    // 🌟 Single ExoPlayer instance with the project's codec-fallback helper.
    val exoPlayer = remember(context, videoUri) {
        ExoPlayerHelper.createExoPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(videoUri, exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs == 0f) {
                    val dur = exoPlayer.duration.toFloat().coerceAtLeast(1000f)
                    durationMs = dur
                    trimStart = 0f
                    trimEnd = dur
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

    // 🌟 Filmstrip frames — extracted off the main thread, never blocking Compose.
    LaunchedEffect(videoUri) {
        frames = withContext(Dispatchers.IO) {
            extractFilmstripFrames(context, videoUri, FILMSTRIP_CELLS)
        }
    }

    // 🌟 Bounded playback engine + playhead readout (loops inside trim window).
    // Keyed on the player only — trim/playing/scrubbing are read as current
    // state each tick, so drags never churn the coroutine.
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing && durationMs > 0f) {
                val pos = exoPlayer.currentPosition.toFloat()
                playheadMs = pos
                if (isPlaying && (pos >= trimEnd || pos < trimStart)) {
                    exoPlayer.seekTo(trimStart.toLong())
                    playheadMs = trimStart
                }
            }
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
    ) {
        // ── Top glass toolbar ───────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(TrimBarTop, TrimBarBottom)))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassChip(onClick = { if (!isCompressing) onCancel() }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = TrimTextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Text(
                    text = "Edit Video",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    color = TrimTextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(
                        onClick = {
                            isPlaying = !isPlaying
                            exoPlayer.playWhenReady = isPlaying
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play / pause",
                            tint = TrimTextPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    GlassChip(
                        onClick = {
                            onTrimConfirmed(trimStart.toLong(), trimEnd.toLong())
                        },
                        enabled = !isCompressing && durationMs > 0f,
                        background = if (!isCompressing && durationMs > 0f) TrimAccent
                        else Color.White.copy(alpha = 0.07f),
                        border = if (!isCompressing && durationMs > 0f) Color.White.copy(alpha = 0.25f)
                        else TrimHairline
                    ) {
                        if (isCompressing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save trim",
                                tint = if (durationMs > 0f) Color.White else TrimTextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, TrimHairline, Color.Transparent)
                        )
                    )
            )
        }

        // ── Preview ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
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
        }

        // ── Filmstrip + trim handles + readouts ─────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(TrimBarBottom, TrimBarTop)))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, TrimHairline, Color.Transparent)
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TrimCell)
                    .drawWithContent {
                        drawContent()
                        val dur = durationMs
                        if (dur > 0f && trimEnd > trimStart) {
                            val w = size.width
                            val h = size.height
                            val sx = (trimStart / dur) * w
                            val ex = (trimEnd / dur) * w

                            // Dim everything outside the trim window.
                            val dim = Color.Black.copy(alpha = 0.65f)
                            drawRect(dim, topLeft = Offset.Zero, size = Size(sx, h))
                            drawRect(
                                dim,
                                topLeft = Offset(ex, 0f),
                                size = Size((w - ex).coerceAtLeast(0f), h)
                            )

                            // Accent frame around the active trim region.
                            drawRoundRect(
                                color = TrimAccent,
                                topLeft = Offset(sx, 0f),
                                size = Size((ex - sx).coerceAtLeast(1f), h),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // Grab thumbs with grip notches.
                            val tw = 8.dp.toPx()
                            for (x in listOf(sx, ex)) {
                                drawRoundRect(
                                    color = TrimAccent,
                                    topLeft = Offset(x - tw / 2f, 0f),
                                    size = Size(tw, h),
                                    cornerRadius = CornerRadius(4.dp.toPx())
                                )
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.85f),
                                    topLeft = Offset(x - tw * 0.15f, h * 0.30f),
                                    size = Size(tw * 0.3f, h * 0.40f),
                                    cornerRadius = CornerRadius(2.dp.toPx())
                                )
                            }

                            // Playhead hairline.
                            if (playheadMs in trimStart..trimEnd) {
                                val px = (playheadMs / dur) * w
                                drawLine(
                                    color = Color.White.copy(alpha = 0.85f),
                                    start = Offset(px, 0f),
                                    end = Offset(px, h),
                                    strokeWidth = 1.5.dp.toPx()
                                )
                            }
                        }
                    }
                    .pointerInput(videoUri, durationMs) {
                        val thumbTouch = 28.dp.toPx()
                        forEachGesture {
                            awaitPointerEventScope {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val dur = durationMs
                                if (dur > 0f) {
                                    val w = size.width.toFloat()
                                    val sx = (trimStart / dur) * w
                                    val ex = (trimEnd / dur) * w
                                    // 0 = start thumb, 1 = end thumb, 2 = slide window.
                                    val mode = when {
                                        abs(down.position.x - sx) <= thumbTouch -> 0
                                        abs(down.position.x - ex) <= thumbTouch -> 1
                                        down.position.x in sx..ex -> 2
                                        down.position.x < sx -> 0
                                        else -> 1
                                    }
                                    val wasPlaying = isPlaying
                                    val windowMs = trimEnd - trimStart
                                    val anchorStart = trimStart
                                    scrubbing = true
                                    isPlaying = false
                                    exoPlayer.playWhenReady = false

                                    var prevX = down.position.x
                                    loop@ while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break@loop
                                        val x = pressed[0].position.x
                                        val ms = (x / w).coerceIn(0f, 1f) * dur
                                        when (mode) {
                                            0 -> {
                                                trimStart = ms.coerceIn(0f, trimEnd - MIN_TRIM_MS)
                                                exoPlayer.seekTo(trimStart.toLong())
                                            }
                                            1 -> {
                                                trimEnd = ms.coerceIn(trimStart + MIN_TRIM_MS, dur)
                                                exoPlayer.seekTo(trimEnd.toLong())
                                            }
                                            else -> {
                                                val delta = ((x - prevX) / w) * dur
                                                val ns = (anchorStart + delta)
                                                    .coerceIn(0f, (dur - windowMs).coerceAtLeast(0f))
                                                trimStart = ns
                                                trimEnd = ns + windowMs
                                            }
                                        }
                                        prevX = x
                                        playheadMs = exoPlayer.currentPosition.toFloat()
                                        event.changes.forEach { it.consume() }
                                    }

                                    scrubbing = false
                                    exoPlayer.seekTo(trimStart.toLong())
                                    playheadMs = trimStart
                                    if (wasPlaying) {
                                        isPlaying = true
                                        exoPlayer.playWhenReady = true
                                    }
                                }
                            }
                        }
                    }
            ) {
                if (frames.isEmpty()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        repeat(FILMSTRIP_CELLS) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(TrimCell)
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.04f)
                                    )
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        frames.forEach { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatMs(trimStart.toLong())} – ${formatMs(trimEnd.toLong())}",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TrimTextPrimary
                )
                Text(
                    text = "Clip ${((trimEnd - trimStart) / 1000).toInt()}s",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TrimTextSecondary
                )
            }

            if (isCompressing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { compressionProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = TrimAccent,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Trimming & transcoding…",
                        fontSize = 12.sp,
                        color = TrimTextSecondary
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** Frosted circular chip shared by the editor toolbars. */
@Composable
private fun GlassChip(
    onClick: () -> Unit,
    enabled: Boolean = true,
    background: Color = Color.White.copy(alpha = 0.07f),
    border: Color = TrimHairline,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Pulls [count] evenly spaced frames via MediaMetadataRetriever on the calling
 * (IO) thread. Frames are downscaled to tiny squares — the filmstrip only
 * needs a visual gist, and memory stays trivial.
 */
private fun extractFilmstripFrames(
    context: Context,
    uri: Uri,
    count: Int
): List<ImageBitmap> {
    return try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration <= 0L) return emptyList()

            val out = ArrayList<ImageBitmap>(count)
            for (i in 0 until count) {
                val atMs = (duration * i / count) + (duration / (count * 2L))
                val frame = retriever.getFrameAtTime(
                    atMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val scaled = Bitmap.createScaledBitmap(frame, 96, 96, true)
                if (scaled !== frame) frame.recycle()
                out.add(scaled.asImageBitmap())
            }
            out
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
