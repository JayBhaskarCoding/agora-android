package com.example.agora.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* ═══════════════════════════════════════════════════════════════════════
 * ✦ Agora Crop Studio — flagship custom image editor, built from scratch.
 *
 * Architecture (mathematically sound by construction):
 *  • The photo renders with ContentScale.Fit — NEVER FillBounds — so the
 *    source aspect ratio can not be distorted on screen.
 *  • The exact pixel bounds of the drawn image are computed analytically
 *    (same uniform-scale + centering math Fit performs), and the crop box
 *    is clamped strictly INSIDE those visual bounds at all times.
 *  • Dragging uses detectDragGestures with generous 48dp invisible touch
 *    targets on all 4 corners and 4 edges; corners win hit-tests first.
 *    Clamping guarantees the left edge can never cross the right edge
 *    (and top never crosses bottom) — no inversion, at any speed.
 *  • Presets (1:1, 4:5, 16:9) snap the crop box to the MAXIMUM rectangle
 *    of that exact ratio that fits inside the drawn image bounds, and
 *    corner/edge drags then lock to that ratio via anchored, exact-ratio,
 *    shrink-to-fit math that cannot break at extremes.
 *  • Save maps the crop box to NORMALIZED (0.0–1.0) coordinates relative
 *    to the drawn bounds, applies them to the full decoded bitmap and runs
 *    Bitmap.createBitmap on Dispatchers.IO — pure pixel crop, zero scaling
 *    or stretching — then hands back a cache-file Uri (the exact contract
 *    the upload pipeline has always consumed).
 * ═══════════════════════════════════════════════════════════════════════ */

/* ───────────────────────── dark glass palette ───────────────────────── */

private val EditorCanvas = Color(0xFF08090F)
private val ChromeDark = Color(0xF20B0C14)
private val ChromeDarker = Color(0xF708090F)
private val Hairline = Color.White.copy(alpha = 0.12f)
private val SaveAccent = Color(0xFF8A63FF)
private val SaveGradient = listOf(Color(0xFF8A63FF), Color(0xFFC86BFF))
private val DimScrim = Color.Black.copy(alpha = 0.62f)
private val TextIdle = Color.White.copy(alpha = 0.60f)
private val TextMuted = Color.White.copy(alpha = 0.70f)

private val CropRatioLabels = listOf("Free", "1:1", "4:5", "16:9")

/* Handle ids — corners, edges (clockwise from top-left), plus move/none. */
private const val NO_HANDLE = -1
private const val HANDLE_TL = 0
private const val HANDLE_T = 1
private const val HANDLE_TR = 2
private const val HANDLE_R = 3
private const val HANDLE_BR = 4
private const val HANDLE_B = 5
private const val HANDLE_BL = 6
private const val HANDLE_L = 7
private const val HANDLE_MOVE = 8

/* ───────────────────────── public entry point ───────────────────────── */

/**
 * Full-screen custom crop studio for [sourceUri]. On save, the cropped
 * photo is written to a cache file and its Uri delivered to [onCropped] —
 * the same contract the Supabase/Cloudinary upload pipeline consumes.
 */
@Composable
fun AgoraImageCropDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onCropped: (Uri) -> Unit
) {
    val context = LocalContext.current

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) {
        val decoded = decodeOrientedBitmap(context, sourceUri)
        if (decoded != null) bitmap = decoded else loadFailed = true
    }

    // Transient failure pill — auto-hides after 3s.
    LaunchedEffect(saveFailed) {
        if (saveFailed) {
            delay(3000)
            saveFailed = false
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(EditorCanvas)
        ) {
            val bmp = bitmap
            when {
                bmp != null -> CropStudio(
                    bmp = bmp,
                    isSaving = isSaving,
                    onSavingChange = { isSaving = it },
                    saveFailed = saveFailed,
                    onSaveFailed = { saveFailed = true },
                    onDismiss = { if (!isSaving) onDismiss() },
                    onCropped = onCropped
                )

                loadFailed -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Couldn't load this image.",
                        color = TextMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "Close",
                        color = SaveAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }

                else -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(38.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

/* ───────────────────────── the studio itself ───────────────────────── */

@Composable
private fun CropStudio(
    bmp: Bitmap,
    isSaving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    saveFailed: Boolean,
    onSaveFailed: () -> Unit,
    onDismiss: () -> Unit,
    onCropped: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

    var ratioIndex by remember { mutableIntStateOf(0) }
    var cropRect by remember { mutableStateOf(Rect.Zero) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    // The image bounds cropRect was last built against — lets a stage resize
    // (rotation / split screen) remap the crop box proportionally.
    var boundsForRect by remember { mutableStateOf(Rect.Zero) }
    var activeHandle by remember { mutableIntStateOf(NO_HANDLE) }

    // 🌟 The EXACT pixel bounds where ContentScale.Fit draws the bitmap:
    // uniform scale (min of both axis ratios) + centering — identical math
    // to Fit itself, so the crop box lives in true visual coordinates.
    val imgBounds = remember(bmp, stageSize) {
        fitBounds(bmp.width, bmp.height, stageSize.width.toFloat(), stageSize.height.toFloat())
    }

    LaunchedEffect(imgBounds) {
        if (imgBounds == Rect.Zero) return@LaunchedEffect
        val prev = boundsForRect
        cropRect = if (prev == Rect.Zero || prev.width <= 0f) {
            // First layout: snap to the maximum rect for the active preset
            // (Free = the full drawn image).
            maxRectFor(presetRatio(ratioIndex), imgBounds)
        } else {
            // Proportional remap across stage changes.
            val sx = imgBounds.width / prev.width
            val sy = imgBounds.height / prev.height
            Rect(
                imgBounds.left + (cropRect.left - prev.left) * sx,
                imgBounds.top + (cropRect.top - prev.top) * sy,
                imgBounds.left + (cropRect.right - prev.left) * sx,
                imgBounds.top + (cropRect.bottom - prev.top) * sy
            )
        }
        boundsForRect = imgBounds
    }

    Column(modifier = Modifier.fillMaxSize()) {

        /* ── Top chrome: cancel · title · save ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChromeDark)
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = TextMuted
                )
            }

            Text(
                text = "Edit Photo",
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            val canSave = cropRect != Rect.Zero && !isSaving
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (cropRect == Rect.Zero) SolidColor(Color.White.copy(alpha = 0.10f))
                        else Brush.horizontalGradient(SaveGradient)
                    )
                    .clickable(enabled = canSave) {
                        // Multi-tap guard — one save pipeline at a time.
                        if (isSaving) return@clickable
                        onSavingChange(true)
                        scope.launch {
                            val uri = cropAndSave(context, bmp, imgBounds, cropRect)
                            onSavingChange(false)
                            if (uri != null) onCropped(uri) else onSaveFailed()
                        }
                    }
                    .padding(horizontal = 22.dp, vertical = 9.dp)
            ) {
                if (isSaving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("Save", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Hairline, Color.Transparent))
                )
        )

        /* ── Stage: Fit-rendered photo + crop overlay + gesture layer ── */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { stageSize = it }
        ) {
            // 🌟 ContentScale.Fit — the original aspect ratio is NEVER
            // distorted on screen. FillBounds is banned from this pipeline.
            Image(
                bitmap = imageBitmap,
                contentDescription = "Photo to edit",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            if (imgBounds != Rect.Zero && cropRect != Rect.Zero) {

                // Dim scrim, thirds grid, hairline frame, edge bars and the
                // corner brackets — the visible crop chrome.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val win = cropRect

                    drawRect(DimScrim, topLeft = Offset.Zero, size = Size(size.width, max(0f, win.top)))
                    drawRect(
                        DimScrim,
                        topLeft = Offset(0f, win.bottom),
                        size = Size(size.width, max(0f, size.height - win.bottom))
                    )
                    drawRect(DimScrim, topLeft = Offset(0f, win.top), size = Size(max(0f, win.left), win.height))
                    drawRect(
                        DimScrim,
                        topLeft = Offset(win.right, win.top),
                        size = Size(max(0f, size.width - win.right), win.height)
                    )

                    // Rule of thirds.
                    val grid = Color.White.copy(alpha = 0.16f)
                    val gridW = 1.dp.toPx()
                    drawLine(grid, Offset(win.left + win.width / 3f, win.top), Offset(win.left + win.width / 3f, win.bottom), gridW)
                    drawLine(grid, Offset(win.left + win.width * 2f / 3f, win.top), Offset(win.left + win.width * 2f / 3f, win.bottom), gridW)
                    drawLine(grid, Offset(win.left, win.top + win.height / 3f), Offset(win.right, win.top + win.height / 3f), gridW)
                    drawLine(grid, Offset(win.left, win.top + win.height * 2f / 3f), Offset(win.right, win.top + win.height * 2f / 3f), gridW)

                    // Hairline frame with a soft shadow for bright photos.
                    drawRect(
                        color = Color.Black.copy(alpha = 0.35f),
                        topLeft = Offset(win.left, win.top),
                        size = Size(win.width, win.height),
                        style = Stroke(width = 3.5.dp.toPx())
                    )
                    drawRect(
                        color = Color.White.copy(alpha = 0.90f),
                        topLeft = Offset(win.left, win.top),
                        size = Size(win.width, win.height),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Slim grab bars at the edge midpoints.
                    val bar = Color.White.copy(alpha = 0.55f)
                    val barLong = 16.dp.toPx()
                    val barThick = 2.5.dp.toPx()
                    val mx = win.left + win.width / 2f
                    val my = win.top + win.height / 2f
                    drawRect(bar, topLeft = Offset(mx - barLong / 2f, win.top - barThick / 2f), size = Size(barLong, barThick))
                    drawRect(bar, topLeft = Offset(mx - barLong / 2f, win.bottom - barThick / 2f), size = Size(barLong, barThick))
                    drawRect(bar, topLeft = Offset(win.left - barThick / 2f, my - barLong / 2f), size = Size(barThick, barLong))
                    drawRect(bar, topLeft = Offset(win.right - barThick / 2f, my - barLong / 2f), size = Size(barThick, barLong))

                    // Corner brackets — rounded white Ls, the flagship look.
                    val arm = 22.dp.toPx()
                    val thick = 3.5.dp.toPx()
                    val stroke = Stroke(width = thick, cap = StrokeCap.Round)
                    val white = Color.White
                    // TL
                    drawLine(white, Offset(win.left, win.top), Offset(win.left + arm, win.top), stroke)
                    drawLine(white, Offset(win.left, win.top), Offset(win.left, win.top + arm), stroke)
                    // TR
                    drawLine(white, Offset(win.right, win.top), Offset(win.right - arm, win.top), stroke)
                    drawLine(white, Offset(win.right, win.top), Offset(win.right, win.top + arm), stroke)
                    // BR
                    drawLine(white, Offset(win.right, win.bottom), Offset(win.right - arm, win.bottom), stroke)
                    drawLine(white, Offset(win.right, win.bottom), Offset(win.right, win.bottom - arm), stroke)
                    // BL
                    drawLine(white, Offset(win.left, win.bottom), Offset(win.left + arm, win.bottom), stroke)
                    drawLine(white, Offset(win.left, win.bottom), Offset(win.left, win.bottom - arm), stroke)
                }

                // 🌟 Gesture layer: detectDragGestures with 48dp invisible
                // touch targets (±24dp around every corner and edge line).
                // Corners are hit-tested FIRST so they can never be stolen
                // by an adjacent edge band. Drag math is absolute (gesture
                // start rect + total delta) — no floating-point drift.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(imgBounds) {
                            val touch = 24.dp.toPx()   // half of the 48dp target
                            val minWin = 96.dp.toPx()
                            var gestureStartRect = Rect.Zero
                            var gestureStartOffset = Offset.Zero

                            detectDragGestures(
                                onDragStart = { start ->
                                    gestureStartOffset = start
                                    gestureStartRect = cropRect
                                    activeHandle = handleAt(start, cropRect, touch)
                                },
                                onDrag = { change, _ ->
                                    if (activeHandle == NO_HANDLE) return@detectDragGestures
                                    val total = change.position - gestureStartOffset
                                    cropRect = applyDrag(
                                        start = gestureStartRect,
                                        handle = activeHandle,
                                        total = total,
                                        ratio = presetRatio(ratioIndex),
                                        bounds = imgBounds,
                                        min = minWin
                                    )
                                },
                                onDragEnd = { activeHandle = NO_HANDLE },
                                onDragCancel = { activeHandle = NO_HANDLE }
                            )
                        }
                )
            }

            // Transient save-failure pill.
            if (saveFailed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clip(CircleShape)
                        .background(Color(0xE61C1030))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Couldn't save the edit. Please try again.",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Hairline, Color.Transparent))
                )
        )

        /* ── Bottom chrome: dark glass ratio pills ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(ChromeDark, ChromeDarker)))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CropRatioLabels.forEachIndexed { index, label ->
                val active = index == ratioIndex
                val pillBg by animateColorAsState(
                    targetValue = if (active) SaveAccent else Color.White.copy(alpha = 0.08f),
                    animationSpec = tween(180),
                    label = "CropPillBg$index"
                )
                val pillText by animateColorAsState(
                    targetValue = if (active) Color.White else TextIdle,
                    animationSpec = tween(180),
                    label = "CropPillText$index"
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pillBg)
                        .border(
                            1.dp,
                            if (active) Color.White.copy(alpha = 0.25f) else Hairline,
                            CircleShape
                        )
                        .clickable {
                            ratioIndex = index
                            // 🌟 Snap to the MAXIMUM rectangle of this exact
                            // ratio that fits inside the drawn image bounds.
                            if (imgBounds != Rect.Zero) {
                                cropRect = maxRectFor(presetRatio(index), imgBounds)
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

/* ───────────────────────── crop geometry ───────────────────────── */

/** Exact drawn-image bounds for ContentScale.Fit: uniform scale + centering. */
private fun fitBounds(bmpW: Int, bmpH: Int, stageW: Float, stageH: Float): Rect {
    if (bmpW <= 0 || bmpH <= 0 || stageW <= 0f || stageH <= 0f) return Rect.Zero
    val s = min(stageW / bmpW, stageH / bmpH)
    val w = bmpW * s
    val h = bmpH * s
    return Rect(
        left = (stageW - w) / 2f,
        top = (stageH - h) / 2f,
        right = (stageW + w) / 2f,
        bottom = (stageH + h) / 2f
    )
}

/** null = free-form; otherwise the exact preset ratio. */
private fun presetRatio(index: Int): Float? = when (index) {
    1 -> 1f
    2 -> 4f / 5f
    3 -> 16f / 9f
    else -> null
}

/** The maximum rect of [ratio] that fits inside [bounds], centered. */
private fun maxRectFor(ratio: Float?, bounds: Rect): Rect {
    if (ratio == null) return bounds
    var w = bounds.width
    var h = w / ratio
    if (h > bounds.height) {
        h = bounds.height
        w = h * ratio
    }
    return Rect(
        left = bounds.left + (bounds.width - w) / 2f,
        top = bounds.top + (bounds.height - h) / 2f,
        right = bounds.left + (bounds.width + w) / 2f,
        bottom = bounds.top + (bounds.height + h) / 2f
    )
}

/**
 * Which handle (if any) sits under [p]. Corners are tested FIRST with 48dp
 * invisible squares, then the 48dp edge bands, then the interior (move).
 * Corner-first ordering is what keeps knobs from being stolen by edges.
 */
private fun handleAt(p: Offset, r: Rect, touch: Float): Int {
    val nearL = abs(p.x - r.left) <= touch
    val nearR = abs(p.x - r.right) <= touch
    val nearT = abs(p.y - r.top) <= touch
    val nearB = abs(p.y - r.bottom) <= touch

    return when {
        nearL && nearT -> HANDLE_TL
        nearR && nearT -> HANDLE_TR
        nearR && nearB -> HANDLE_BR
        nearL && nearB -> HANDLE_BL
        nearT && p.x >= r.left - touch && p.x <= r.right + touch -> HANDLE_T
        nearB && p.x >= r.left - touch && p.x <= r.right + touch -> HANDLE_B
        nearL && p.y >= r.top - touch && p.y <= r.bottom + touch -> HANDLE_L
        nearR && p.y >= r.top - touch && p.y <= r.bottom + touch -> HANDLE_R
        r.contains(p) -> HANDLE_MOVE
        else -> NO_HANDLE
    }
}

/**
 * Applies a drag to [start] (the rect captured at gesture start) using the
 * ABSOLUTE total delta — drift-free. Free mode clamps each edge so it can
 * never cross its opposite (no inversion) and never leaves [bounds].
 * Locked mode anchors the opposite corner/edge, derives BOTH dimensions
 * from the drag and shrink-to-fits with ONE uniform factor, so the ratio
 * mathematically cannot break at extremes.
 */
private fun applyDrag(
    start: Rect,
    handle: Int,
    total: Offset,
    ratio: Float?,
    bounds: Rect,
    min0: Float
): Rect {
    if (handle == NO_HANDLE) return start

    // Safety floor: on degenerate bounds (extreme panoramas drawn thinner
    // than the min window) shrink the minimum — coerceIn THROWS when its
    // lower bound exceeds its upper, so this guard is load-bearing.
    val min = minOf(min0, bounds.width / 2f, bounds.height / 2f).coerceAtLeast(1f)

    if (handle == HANDLE_MOVE) {
        val dx = total.x.coerceIn(bounds.left - start.left, bounds.right - start.right)
        val dy = total.y.coerceIn(bounds.top - start.top, bounds.bottom - start.bottom)
        return start.translate(Offset(dx, dy))
    }

    if (ratio == null) {
        var l = start.left
        var t = start.top
        var r = start.right
        var b = start.bottom
        when (handle) {
            HANDLE_TL -> { l += total.x; t += total.y }
            HANDLE_T -> { t += total.y }
            HANDLE_TR -> { r += total.x; t += total.y }
            HANDLE_R -> { r += total.x }
            HANDLE_BR -> { r += total.x; b += total.y }
            HANDLE_B -> { b += total.y }
            HANDLE_BL -> { l += total.x; b += total.y }
            HANDLE_L -> { l += total.x }
        }
        // Strict no-inversion clamping: left can never cross right, top can
        // never cross bottom, and the window never leaves the drawn image.
        l = l.coerceIn(bounds.left, r - min)
        r = r.coerceIn(l + min, bounds.right)
        t = t.coerceIn(bounds.top, b - min)
        b = b.coerceIn(t + min, bounds.bottom)
        return Rect(l, t, r, b)
    }

    // Floors that keep BOTH dimensions ≥ min without breaking the ratio.
    val wFloor = max(min, min * ratio)
    val hFloor = max(min, min / ratio)

    return when (handle) {
        HANDLE_TL -> {
            val aR = start.right
            val aB = start.bottom
            val wX = aR - (start.left + total.x)
            val wY = (aB - (start.top + total.y)) * ratio
            var w = max(wX, wY).coerceAtLeast(min)
            var h = w / ratio
            val k = minOf(1f, (aR - bounds.left) / w, (aB - bounds.top) / h)
            w = (w * k).coerceAtLeast(wFloor)
            h = w / ratio
            Rect(aR - w, aB - h, aR, aB)
        }

        HANDLE_TR -> {
            val aL = start.left
            val aB = start.bottom
            val wX = (start.right + total.x) - aL
            val wY = (aB - (start.top + total.y)) * ratio
            var w = max(wX, wY).coerceAtLeast(min)
            var h = w / ratio
            val k = minOf(1f, (bounds.right - aL) / w, (aB - bounds.top) / h)
            w = (w * k).coerceAtLeast(wFloor)
            h = w / ratio
            Rect(aL, aB - h, aL + w, aB)
        }

        HANDLE_BR -> {
            val aL = start.left
            val aT = start.top
            val wX = (start.right + total.x) - aL
            val wY = (start.bottom + total.y - aT) * ratio
            var w = max(wX, wY).coerceAtLeast(min)
            var h = w / ratio
            val k = minOf(1f, (bounds.right - aL) / w, (bounds.bottom - aT) / h)
            w = (w * k).coerceAtLeast(wFloor)
            h = w / ratio
            Rect(aL, aT, aL + w, aT + h)
        }

        HANDLE_BL -> {
            val aR = start.right
            val aT = start.top
            val wX = aR - (start.left + total.x)
            val wY = (start.bottom + total.y - aT) * ratio
            var w = max(wX, wY).coerceAtLeast(min)
            var h = w / ratio
            val k = minOf(1f, (aR - bounds.left) / w, (bounds.bottom - aT) / h)
            w = (w * k).coerceAtLeast(wFloor)
            h = w / ratio
            Rect(aR - w, aT, aR, aT + h)
        }

        HANDLE_T -> {
            val aB = start.bottom
            val cx = start.center.x
            var h = (aB - (start.top + total.y)).coerceAtLeast(min)
            var w = h * ratio
            val roomW = minOf(cx - bounds.left, bounds.right - cx) * 2f
            val k = minOf(1f, (aB - bounds.top) / h, roomW / w)
            h = (h * k).coerceAtLeast(hFloor)
            w = h * ratio
            Rect(cx - w / 2f, aB - h, cx + w / 2f, aB)
        }

        HANDLE_B -> {
            val aT = start.top
            val cx = start.center.x
            var h = (start.bottom + total.y - aT).coerceAtLeast(min)
            var w = h * ratio
            val roomW = minOf(cx - bounds.left, bounds.right - cx) * 2f
            val k = minOf(1f, (bounds.bottom - aT) / h, roomW / w)
            h = (h * k).coerceAtLeast(hFloor)
            w = h * ratio
            Rect(cx - w / 2f, aT, cx + w / 2f, aT + h)
        }

        HANDLE_R -> {
            val aL = start.left
            val cy = start.center.y
            var w = (start.right + total.x - aL).coerceAtLeast(min)
            var h = w / ratio
            val roomH = minOf(cy - bounds.top, bounds.bottom - cy) * 2f
            val k = minOf(1f, (bounds.right - aL) / w, roomH / h)
            w = (w * k).coerceAtLeast(wFloor)
            h = w / ratio
            Rect(aL, cy - h / 2f, aL + w, cy + h / 2f)
        }

        HANDLE_L -> {
            val aR = start.right
            val cy = start.center.y
            var w = (aR - (start.left + total.x)).coerceAtLeast(min)
            var h = w / ratio
            val roomH = minOf(cy - bounds.top, bounds.bottom - cy) * 2f
            val k = minOf(1f, (aR - bounds.left) / w, roomH / h)
            w = (w * k).coerceAtLeast(wFloor)
            h = w / ratio
            Rect(aR - w, cy - h / 2f, aR, cy + h / 2f)
        }

        else -> start
    }
}

/* ───────────────────────── decode + save (IO) ───────────────────────── */

/**
 * Maps the crop box to NORMALIZED (0.0–1.0) coordinates relative to the
 * drawn image bounds, applies them to the decoded bitmap and crops on
 * Dispatchers.IO. Bitmap.createBitmap copies raw pixels — there is no
 * scaling or stretching anywhere in the output path. Result: JPEG q92 in
 * the cache dir, returned as a file Uri (the upload pipeline's contract).
 */
private suspend fun cropAndSave(
    context: Context,
    bmp: Bitmap,
    bounds: Rect,
    crop: Rect
): Uri? = withContext(Dispatchers.IO) {
    try {
        if (bounds.width <= 0f || bounds.height <= 0f) return@withContext null

        // Normalized crop coordinates against the drawn image bounds.
        val nx = ((crop.left - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val ny = ((crop.top - bounds.top) / bounds.height).coerceIn(0f, 1f)
        val nw = (crop.width / bounds.width).coerceIn(0f, 1f - nx)
        val nh = (crop.height / bounds.height).coerceIn(0f, 1f - ny)

        val x = (nx * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        val y = (ny * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)

        // Both edges derive from the SAME normalized scale; if either would
        // overflow the bitmap, shrink BOTH by one uniform factor k so the
        // output aspect can never skew.
        val wf = nw * bmp.width
        val hf = nh * bmp.height
        if (wf < 1f || hf < 1f) return@withContext null
        val k = minOf(1f, (bmp.width - x) / wf, (bmp.height - y) / hf)
        val w = (wf * k).roundToInt().coerceIn(1, bmp.width - x)
        val h = (hf * k).roundToInt().coerceIn(1, bmp.height - y)

        val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
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
 * Decodes the picked image off the main thread with EXIF orientation
 * applied so phone photos are never sideways. A uniform power-of-two
 * downsample caps the longest edge at 2160px — aspect ratio untouched,
 * normalized crop mapping stays pixel-exact, and 50–200MP sensors can't
 * OOM the editor.
 */
private suspend fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
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
