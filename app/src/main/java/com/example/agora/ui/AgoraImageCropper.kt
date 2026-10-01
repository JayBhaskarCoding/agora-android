package com.example.agora.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.agora.ui.theme.LocalDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * ✦ AGORA CROP STUDIO v2 — Compose-native replacement for the legacy uCrop flow.
 *
 *  - FREE-FORM CROP WINDOW: drag the four corner knobs or the four edge handles
 *    to resize the crop region to anything you want (clamped to the stage and a
 *    sane minimum). Picking a ratio pill (1:1 / 4:5 / 16:9) locks resizing to
 *    that aspect; "Free" releases the lock.
 *  - FLUID IMAGE CONTROL: one-finger drag pans, two-finger pinch zooms around
 *    the pinch centroid; the image is always clamped so it keeps COVERING the
 *    crop window, and the stage clips hard so nothing ever escapes the layout.
 *  - Deep dim (0.80) outside the window so the crop region reads instantly.
 *  - Dark glassmorphic chrome: near-black canvas, translucent #12141D toolbars
 *    with fade-seam hairlines, accent-lit confirm.
 *  - EXIF-aware decode downsampled to ≤2160px; deterministic window→bitmap
 *    mapping saves JPEG 92 to a cache file Uri — the same contract the upload
 *    pipeline consumed from uCrop.
 */


/** Stage facts shared by the draw pass, the gesture handler and the saver. */
private data class CropGeometry(
    val stageW: Float = 0f,
    val stageH: Float = 0f,
    /** Stage px per source-bitmap px at zoom == 1 (bitmap FITS the stage). */
    val fitScale: Float = 0f,
    val bmpW: Int = 0,
    val bmpH: Int = 0
) {
    val isReady: Boolean get() = fitScale > 0f && bmpW > 0 && bmpH > 0
    val stageRect: Rect get() = Rect(0f, 0f, stageW, stageH)
}

private val CropRatioLabels = listOf("Free", "1:1", "4:5", "16:9")

/** Handle ids: 0 TL, 1 T, 2 TR, 3 R, 4 BR, 5 B, 6 BL, 7 L. */
private const val NO_HANDLE = -1

@Composable
fun AgoraImageCropDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onCropped: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var ratioIndex by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var winRect by remember { mutableStateOf(Rect.Zero) }

    val geometry = remember { mutableStateOf(CropGeometry()) }

    // 🌟 Theme-aware editor chrome: obsidian noir in dark mode; frosted pearl
    //    bars, charcoal ink and an indigo accent in light mode. The media
    //    viewport (dim wash, grid lines, corner knobs) deliberately stays dark
    //    in both themes — the photo-editor convention for accurate colour
    //    judgement against the image.
    val isDarkChrome = LocalDarkTheme.current
    val CropCanvas = if (isDarkChrome) Color(0xFF06070C) else Color(0xFFF1F2F4)
    val CropToolbarTop = if (isDarkChrome) Color(0xFF12141D).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.88f)
    val CropToolbarBottom = if (isDarkChrome) Color(0xFF12141D).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.72f)
    val CropAccent = if (isDarkChrome) Color(0xFF818CF8) else Color(0xFF4F46E5)
    val CropTextPrimary = if (isDarkChrome) Color(0xFFF4F5FA) else Color(0xFF1C1C1E)
    val CropTextSecondary = if (isDarkChrome) Color(0xFFA9AEC0) else Color(0xFF5B5F66)
    val CropHairline = if (isDarkChrome) Color.White.copy(alpha = 0.10f) else Color(0xFF1C1C1E).copy(alpha = 0.10f)
    val CropChipIdle = if (isDarkChrome) Color.White.copy(alpha = 0.07f) else Color(0xFF1C1C1E).copy(alpha = 0.05f)
    val CropChipBorder = if (isDarkChrome) Color.White.copy(alpha = 0.25f) else Color(0xFF1C1C1E).copy(alpha = 0.22f)

    LaunchedEffect(sourceUri) {
        val decoded = decodeOrientedBitmap(context, sourceUri)
        if (decoded != null) bitmap = decoded else loadFailed = true
    }

    if (loadFailed) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CropCanvas)
        ) {
            // ── Top glass toolbar ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(CropToolbarTop, CropToolbarBottom)))
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(CropChipIdle)
                            .border(1.dp, CropHairline, CircleShape)
                            .clickable { if (!isSaving) onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = CropTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = "Edit Photo",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        color = CropTextPrimary
                    )

                    val canConfirm = bitmap != null && !isSaving
                    val confirmBg by animateColorAsState(
                        targetValue = if (canConfirm) CropAccent else CropChipIdle,
                        animationSpec = tween(180),
                        label = "CropConfirmBg"
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(confirmBg)
                            .border(
                                1.dp,
                                if (canConfirm) CropChipBorder else CropHairline,
                                CircleShape
                            )
                            .clickable(enabled = canConfirm) {
                                val bmp = bitmap ?: return@clickable
                                isSaving = true
                                scope.launch {
                                    val out = saveCrop(
                                        context = context,
                                        source = bmp,
                                        g = geometry.value,
                                        window = winRect,
                                        zoom = zoom,
                                        offset = panOffset
                                    )
                                    isSaving = false
                                    if (out != null) {
                                        onCropped(out)
                                    } else {
                                        Toast.makeText(context, "Couldn't save the crop", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save crop",
                                tint = if (canConfirm) Color.White else CropTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, CropHairline, Color.Transparent)
                            )
                        )
                )
            }

            // ── Crop stage ──────────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                val bmp = bitmap
                if (bmp == null) {
                    if (!loadFailed) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(34.dp),
                            strokeWidth = 3.dp,
                            color = CropAccent
                        )
                    }
                } else {
                    val stageW = constraints.maxWidth.toFloat()
                    val stageH = constraints.maxHeight.toFloat()
                    val fitScale = minOf(stageW / bmp.width, stageH / bmp.height)
                    geometry.value = CropGeometry(stageW, stageH, fitScale, bmp.width, bmp.height)
                    val g = geometry.value

                    // First layout: seed the window with the source aspect, centered.
                    if (winRect == Rect.Zero && g.isReady) {
                        winRect = centeredWindowFor(bmp.width.toFloat() / bmp.height, g)
                    }

                    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(bmp) {
                                // Corners get a generous 36dp grab zone (checked
                                // first); edges a slimmer 20dp band.
                                val cornerTouch = 36.dp.toPx()
                                val edgeTouch = 20.dp.toPx()
                                val minWin = 96.dp.toPx()
                                forEachGesture {
                                    awaitPointerEventScope {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var handle = NO_HANDLE
                                        if (geometry.value.isReady) {
                                            handle = handleAt(
                                                p = down.position,
                                                r = winRect,
                                                cornerTouch = cornerTouch,
                                                edgeTouch = edgeTouch
                                            )
                                        }
                                        var prev = down.position
                                        var prevCentroid = down.position
                                        var prevDist = 0f

                                        loop@ while (true) {
                                            val event = awaitPointerEvent()
                                            val pressed = event.changes.filter { it.pressed }
                                            if (pressed.isEmpty()) break@loop
                                            val geo = geometry.value
                                            if (!geo.isReady) break@loop

                                            if (pressed.size >= 2) {
                                                // Pinch zoom around centroid + two-finger pan.
                                                val a = pressed[0].position
                                                val b = pressed[1].position
                                                val dist = (a - b).getDistance()
                                                val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                                if (prevDist > 0f) {
                                                    applyZoomPan(
                                                        geo = geo,
                                                        win = winRect,
                                                        zoomNow = zoom,
                                                        zoomTarget = zoom * dist / prevDist,
                                                        panNow = panOffset,
                                                        centroid = centroid,
                                                        panDelta = centroid - prevCentroid,
                                                        setZoom = { zoom = it },
                                                        setPan = { panOffset = it }
                                                    )
                                                }
                                                prevDist = dist
                                                prevCentroid = centroid
                                                // Keep single-finger `prev` fresh so
                                                // returning from pinch to pan can't jump.
                                                prev = pressed[0].position
                                                handle = NO_HANDLE
                                            } else if (handle != NO_HANDLE) {
                                                // Drag a corner/edge handle: resize the window.
                                                val ratio = ratioLock(ratioIndex, geo)
                                                winRect = resizeWindow(
                                                    current = winRect,
                                                    handle = handle,
                                                    pointer = pressed[0].position,
                                                    ratio = ratio,
                                                    stage = geo.stageRect,
                                                    min = minWin
                                                )
                                                coverAndClamp(
                                                    geo = geo,
                                                    win = winRect,
                                                    zoomNow = zoom,
                                                    panNow = panOffset,
                                                    setZoom = { zoom = it },
                                                    setPan = { panOffset = it }
                                                )
                                            } else {
                                                // One-finger pan of the image.
                                                val delta = pressed[0].position - prev
                                                prev = pressed[0].position
                                                panOffset = clampOffset(
                                                    geo = geo,
                                                    win = winRect,
                                                    zoomNow = zoom,
                                                    candidate = panOffset + delta
                                                )
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val geo = geometry.value
                            val win = winRect
                            if (!geo.isReady || win == Rect.Zero) return@Canvas

                            val s = geo.fitScale * zoom
                            val imgW = geo.bmpW * s
                            val imgH = geo.bmpH * s
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val off = clampOffset(geo, win, zoom, panOffset)
                            val imgLeft = cx + off.x - imgW / 2f
                            val imgTop = cy + off.y - imgH / 2f

                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                                dstSize = IntSize(
                                    imgW.roundToInt().coerceAtLeast(1),
                                    imgH.roundToInt().coerceAtLeast(1)
                                )
                            )

                            // Deep dim outside the crop window.
                            val dim = Color.Black.copy(alpha = 0.80f)
                            drawRect(dim, topLeft = Offset(0f, 0f), size = size.copy(height = win.top))
                            drawRect(
                                dim,
                                topLeft = Offset(0f, win.bottom),
                                size = size.copy(height = (size.height - win.bottom).coerceAtLeast(0f))
                            )
                            drawRect(
                                dim,
                                topLeft = Offset(0f, win.top),
                                size = size.copy(width = win.left, height = win.height)
                            )
                            drawRect(
                                dim,
                                topLeft = Offset(win.right, win.top),
                                size = size.copy(
                                    width = (size.width - win.right).coerceAtLeast(0f),
                                    height = win.height
                                )
                            )

                            // Rule-of-thirds grid, whisper-quiet.
                            val grid = Color.White.copy(alpha = 0.12f)
                            val gridStroke = 1.dp.toPx()
                            for (i in 1..2) {
                                val gx = win.left + win.width * i / 3f
                                drawLine(grid, Offset(gx, win.top), Offset(gx, win.bottom), gridStroke)
                                val gy = win.top + win.height * i / 3f
                                drawLine(grid, Offset(win.left, gy), Offset(win.right, gy), gridStroke)
                            }

                            // Hairline frame.
                            drawRect(
                                color = Color.White.copy(alpha = 0.35f),
                                topLeft = Offset(win.left, win.top),
                                size = size.copy(width = win.width, height = win.height),
                                style = Stroke(width = 1.5.dp.toPx())
                            )

                            // Edge handles: slim grab bars at edge midpoints.
                            val bar = Color.White.copy(alpha = 0.55f)
                            val barLong = 16.dp.toPx()
                            val barThick = 2.5.dp.toPx()
                            val mx = win.left + win.width / 2f
                            val my = win.top + win.height / 2f
                            drawRect(bar, topLeft = Offset(mx - barLong / 2f, win.top - barThick / 2f), size = size.copy(width = barLong, height = barThick))
                            drawRect(bar, topLeft = Offset(mx - barLong / 2f, win.bottom - barThick / 2f), size = size.copy(width = barLong, height = barThick))
                            drawRect(bar, topLeft = Offset(win.left - barThick / 2f, my - barLong / 2f), size = size.copy(width = barThick, height = barLong))
                            drawRect(bar, topLeft = Offset(win.right - barThick / 2f, my - barLong / 2f), size = size.copy(width = barThick, height = barLong))

                            // Corner knobs — the free-crop handles.
                            val knob = 5.5.dp.toPx()
                            drawCircle(Color.Black.copy(alpha = 0.45f), radius = knob + 1.5.dp.toPx(), center = Offset(win.left, win.top))
                            drawCircle(Color.Black.copy(alpha = 0.45f), radius = knob + 1.5.dp.toPx(), center = Offset(win.right, win.top))
                            drawCircle(Color.Black.copy(alpha = 0.45f), radius = knob + 1.5.dp.toPx(), center = Offset(win.left, win.bottom))
                            drawCircle(Color.Black.copy(alpha = 0.45f), radius = knob + 1.5.dp.toPx(), center = Offset(win.right, win.bottom))
                            drawCircle(Color.White, radius = knob, center = Offset(win.left, win.top))
                            drawCircle(Color.White, radius = knob, center = Offset(win.right, win.top))
                            drawCircle(Color.White, radius = knob, center = Offset(win.left, win.bottom))
                            drawCircle(Color.White, radius = knob, center = Offset(win.right, win.bottom))
                        }
                    }
                }
            }

            // ── Bottom glass bar: ratio pills ───────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(CropToolbarBottom, CropToolbarTop)))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, CropHairline, Color.Transparent)
                            )
                        )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CropRatioLabels.forEachIndexed { index, label ->
                        val active = index == ratioIndex
                        val pillBg by animateColorAsState(
                            targetValue = if (active) CropAccent else CropChipIdle,
                            animationSpec = tween(180),
                            label = "RatioPillBg$index"
                        )
                        val pillText by animateColorAsState(
                            targetValue = if (active) Color.White else CropTextSecondary,
                            animationSpec = tween(180),
                            label = "RatioPillText$index"
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(pillBg)
                                .border(
                                    1.dp,
                                    if (active) CropChipBorder else CropHairline,
                                    CircleShape
                                )
                                .clickable {
                                    ratioIndex = index
                                    val geo = geometry.value
                                    if (geo.isReady) {
                                        // 🌟 Deterministic preset framing: a fresh
                                        // centered window at the exact ratio, zoom
                                        // reset to the precise cover value and pan
                                        // re-centered — no inherited zoom/pan skew
                                        // bleeding across presets.
                                        val newWin = centeredWindowFor(ratioValue(index, geo), geo)
                                        winRect = newWin
                                        val coverZoom = maxOf(
                                            newWin.width / (geo.bmpW * geo.fitScale),
                                            newWin.height / (geo.bmpH * geo.fitScale)
                                        ).coerceIn(1f, 8f)
                                        zoom = coverZoom
                                        panOffset = clampOffset(geo, newWin, coverZoom, Offset.Zero)
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = pillText,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ───────────────────────── crop math ───────────────────────── */

private fun ratioValue(index: Int, g: CropGeometry): Float = when (index) {
    1 -> 1f
    2 -> 4f / 5f
    3 -> 16f / 9f
    else -> g.bmpW.toFloat() / g.bmpH.toFloat()
}

/** null = free-form resize; otherwise resizing stays locked to the aspect. */
private fun ratioLock(index: Int, g: CropGeometry): Float? =
    if (index == 0) null else ratioValue(index, g)

private fun centeredWindowFor(ratio: Float, g: CropGeometry): Rect {
    var w = g.stageW
    var h = w / ratio
    if (h > g.stageH) {
        h = g.stageH
        w = h * ratio
    }
    return Rect(
        left = (g.stageW - w) / 2f,
        top = (g.stageH - h) / 2f,
        right = (g.stageW + w) / 2f,
        bottom = (g.stageH + h) / 2f
    )
}

/**
 * Which handle (if any) sits under the touch point. Corners use a larger
 * radius than edges and are tested first, so grabbing a knob always drags
 * BOTH adjacent bounds at once (TL moves left+top, BR moves right+bottom…).
 */
private fun handleAt(p: Offset, r: Rect, cornerTouch: Float, edgeTouch: Float): Int {
    val nearL = abs(p.x - r.left) <= edgeTouch
    val nearR = abs(p.x - r.right) <= edgeTouch
    val nearT = abs(p.y - r.top) <= edgeTouch
    val nearB = abs(p.y - r.bottom) <= edgeTouch
    val cornerL = abs(p.x - r.left) <= cornerTouch
    val cornerR = abs(p.x - r.right) <= cornerTouch
    val cornerT = abs(p.y - r.top) <= cornerTouch
    val cornerB = abs(p.y - r.bottom) <= cornerTouch
    val inX = p.x >= r.left - edgeTouch && p.x <= r.right + edgeTouch
    val inY = p.y >= r.top - edgeTouch && p.y <= r.bottom + edgeTouch
    return when {
        // 🌟 Corners FIRST. A knob's 36dp zone overlaps the 20dp edge bands, so
        // interleaving edge branches before corner branches let the top / right /
        // bottom edges swallow TR, BR and BL touches — those knobs felt dead.
        cornerL && cornerT -> 0
        cornerR && cornerT -> 2
        cornerR && cornerB -> 4
        cornerL && cornerB -> 6
        nearT && inX -> 1
        nearR && inY -> 3
        nearB && inX -> 5
        nearL && inY -> 7
        else -> NO_HANDLE
    }
}

/** Resize the window by dragging [handle] to [pointer], ratio-locked if asked. */
private fun resizeWindow(
    current: Rect,
    handle: Int,
    pointer: Offset,
    ratio: Float?,
    stage: Rect,
    min: Float
): Rect {
    var l = current.left
    var t = current.top
    var r = current.right
    var b = current.bottom

    when (handle) {
        0 -> { l = pointer.x; t = pointer.y }
        1 -> { t = pointer.y }
        2 -> { r = pointer.x; t = pointer.y }
        3 -> { r = pointer.x }
        4 -> { r = pointer.x; b = pointer.y }
        5 -> { b = pointer.y }
        6 -> { l = pointer.x; b = pointer.y }
        7 -> { l = pointer.x }
    }

    // Clamp against stage + minimum size.
    l = l.coerceIn(stage.left, r - min)
    r = r.coerceIn(l + min, stage.right)
    t = t.coerceIn(stage.top, b - min)
    b = b.coerceIn(t + min, stage.bottom)

    if (ratio != null) {
        // 🌟 Exact-ratio resize. Anchor the opposite corner/edge, derive BOTH
        // dimensions from the drag, then shrink-to-fit the stage with ONE
        // uniform factor k. The old clamp-then-fixup pass nudged single axes at
        // stage extremes and silently broke the 1:1 / 4:5 / 16:9 lock — saved
        // crops came out skewed away from the chosen preset.
        when (handle) {
            0 -> { // TL — anchor bottom-right
                var w = (r - pointer.x).coerceAtLeast(min)
                var h = w / ratio
                val k = minOf(1f, (r - stage.left) / w, (b - stage.top) / h)
                w = (w * k).coerceAtLeast(min); h = w / ratio
                l = r - w; t = b - h
            }
            2 -> { // TR — anchor bottom-left
                var w = (pointer.x - l).coerceAtLeast(min)
                var h = w / ratio
                val k = minOf(1f, (stage.right - l) / w, (b - stage.top) / h)
                w = (w * k).coerceAtLeast(min); h = w / ratio
                r = l + w; t = b - h
            }
            4 -> { // BR — anchor top-left
                var w = (pointer.x - l).coerceAtLeast(min)
                var h = w / ratio
                val k = minOf(1f, (stage.right - l) / w, (stage.bottom - t) / h)
                w = (w * k).coerceAtLeast(min); h = w / ratio
                r = l + w; b = t + h
            }
            6 -> { // BL — anchor top-right
                var w = (r - pointer.x).coerceAtLeast(min)
                var h = w / ratio
                val k = minOf(1f, (r - stage.left) / w, (stage.bottom - t) / h)
                w = (w * k).coerceAtLeast(min); h = w / ratio
                l = r - w; b = t + h
            }
            1 -> { // Top edge — anchor bottom, keep horizontal center
                var h = (b - pointer.y).coerceAtLeast(min)
                var w = h * ratio
                val cx = (l + r) / 2f
                val roomW = minOf(cx - stage.left, stage.right - cx) * 2f
                val k = minOf(1f, (b - stage.top) / h, roomW / w)
                h = (h * k).coerceAtLeast(min); w = h * ratio
                t = b - h; l = cx - w / 2f; r = cx + w / 2f
            }
            5 -> { // Bottom edge — anchor top, keep horizontal center
                var h = (pointer.y - t).coerceAtLeast(min)
                var w = h * ratio
                val cx = (l + r) / 2f
                val roomW = minOf(cx - stage.left, stage.right - cx) * 2f
                val k = minOf(1f, (stage.bottom - t) / h, roomW / w)
                h = (h * k).coerceAtLeast(min); w = h * ratio
                b = t + h; l = cx - w / 2f; r = cx + w / 2f
            }
            3 -> { // Right edge — anchor left, keep vertical center
                var w = (pointer.x - l).coerceAtLeast(min)
                var h = w / ratio
                val cy = (t + b) / 2f
                val roomH = minOf(cy - stage.top, stage.bottom - cy) * 2f
                val k = minOf(1f, (stage.right - l) / w, roomH / h)
                w = (w * k).coerceAtLeast(min); h = w / ratio
                r = l + w; t = cy - h / 2f; b = cy + h / 2f
            }
            7 -> { // Left edge — anchor right, keep vertical center
                var w = (r - pointer.x).coerceAtLeast(min)
                var h = w / ratio
                val cy = (t + b) / 2f
                val roomH = minOf(cy - stage.top, stage.bottom - cy) * 2f
                val k = minOf(1f, (r - stage.left) / w, roomH / h)
                w = (w * k).coerceAtLeast(min); h = w / ratio
                l = r - w; t = cy - h / 2f; b = cy + h / 2f
            }
        }
        return Rect(l, t, r, b)
    }
    return Rect(l, t, r, b)
}

/** Clamp a candidate pan offset so the image keeps covering the window. */
private fun clampOffset(
    geo: CropGeometry,
    win: Rect,
    zoomNow: Float,
    candidate: Offset
): Offset {
    val imgW = geo.bmpW * geo.fitScale * zoomNow
    val imgH = geo.bmpH * geo.fitScale * zoomNow
    val cx = geo.stageW / 2f
    val cy = geo.stageH / 2f
    val oxMin = win.right - cx - imgW / 2f
    val oxMax = win.left - cx + imgW / 2f
    val oyMin = win.bottom - cy - imgH / 2f
    val oyMax = win.top - cy + imgH / 2f
    return Offset(
        x = if (oxMin <= oxMax) candidate.x.coerceIn(oxMin, oxMax) else 0f,
        y = if (oyMin <= oyMax) candidate.y.coerceIn(oyMin, oyMax) else 0f
    )
}

/** Raise zoom until the image covers the (possibly resized) window, then clamp pan. */
private fun coverAndClamp(
    geo: CropGeometry,
    win: Rect,
    zoomNow: Float,
    panNow: Offset,
    setZoom: (Float) -> Unit,
    setPan: (Offset) -> Unit
) {
    val zoomMin = max(
        win.width / (geo.bmpW * geo.fitScale),
        win.height / (geo.bmpH * geo.fitScale)
    )
    val z = zoomNow.coerceAtLeast(zoomMin).coerceAtMost(8f)
    setZoom(z)
    setPan(clampOffset(geo, win, z, panNow))
}

/** Pinch: zoom around the centroid, plus two-finger pan. */
private fun applyZoomPan(
    geo: CropGeometry,
    win: Rect,
    zoomNow: Float,
    zoomTarget: Float,
    panNow: Offset,
    centroid: Offset,
    panDelta: Offset,
    setZoom: (Float) -> Unit,
    setPan: (Offset) -> Unit
) {
    val zoomMin = max(
        win.width / (geo.bmpW * geo.fitScale),
        win.height / (geo.bmpH * geo.fitScale)
    )
    val z = zoomTarget.coerceIn(zoomMin, 8f)
    val sOld = geo.fitScale * zoomNow
    val sNew = geo.fitScale * z
    val cx = geo.stageW / 2f
    val cy = geo.stageH / 2f
    // Keep the image point under the pinch centroid pinned while scaling.
    val cur = clampOffset(geo, win, zoomNow, panNow)
    val imgX = (centroid.x - cx - cur.x) / sOld
    val imgY = (centroid.y - cy - cur.y) / sOld
    val pinned = Offset(
        x = centroid.x - cx - imgX * sNew,
        y = centroid.y - cy - imgY * sNew
    )
    setZoom(z)
    setPan(clampOffset(geo, win, z, pinned + panDelta))
}

/* ───────────────────────── decode + save ───────────────────────── */

/**
 * Maps the crop window back to source-bitmap pixels and saves the result as a
 * JPEG in the cache dir — the same file-Uri contract uCrop used, so the
 * Supabase upload pipeline consumes it unchanged.
 */
private suspend fun saveCrop(
    context: android.content.Context,
    source: Bitmap,
    g: CropGeometry,
    window: Rect,
    zoom: Float,
    offset: Offset
): Uri? = withContext(Dispatchers.Default) {
    try {
        if (!g.isReady || window == Rect.Zero) return@withContext null

        val s = g.fitScale * zoom
        val imgW = g.bmpW * s
        val imgH = g.bmpH * s
        val off = clampOffset(g, window, zoom, offset)
        val imgLeft = g.stageW / 2f + off.x - imgW / 2f
        val imgTop = g.stageH / 2f + off.y - imgH / 2f

        val x = ((window.left - imgLeft) / s).roundToInt().coerceIn(0, source.width - 1)
        val y = ((window.top - imgTop) / s).roundToInt().coerceIn(0, source.height - 1)
        // 🌟 Aspect-exact output: both edges derive from the window through the
        // SAME uniform scale s; if either would overflow the bitmap, shrink BOTH
        // by one factor k. Clamping width and height independently (the old way)
        // skewed the saved pixels away from the chosen 1:1 / 4:5 / 16:9.
        val wf = window.width / s
        val hf = window.height / s
        val k = minOf(1f, (source.width - x) / wf, (source.height - y) / hf)
        val w = (wf * k).roundToInt().coerceIn(1, source.width - x)
        val h = (hf * k).roundToInt().coerceIn(1, source.height - y)

        val cropped = Bitmap.createBitmap(source, x, y, w, h)
        val file = File(context.cacheDir, "crop_${UUID.randomUUID()}.jpg")
        file.outputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        Uri.fromFile(file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Decodes the picked image off the main thread, downsampled so the longest
 * edge is ≤ 2160px, and applies EXIF orientation so phone photos aren't sideways.
 */
private suspend fun decodeOrientedBitmap(
    context: android.content.Context,
    uri: Uri
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2160) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return@withContext null

        val orientation = try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { postRotate(rotation) }, true
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
