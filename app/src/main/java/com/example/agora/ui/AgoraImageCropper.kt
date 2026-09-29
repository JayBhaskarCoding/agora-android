package com.example.agora.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * ✦ AGORA CROP STUDIO — the Compose-native replacement for the legacy uCrop
 * activity flow. One immersive full-screen dialog:
 *
 *  - fluid pinch-to-zoom + pan (detectTransformGestures), the image always
 *    covers the crop window (Instagram-style clamping),
 *  - instant aspect pills at the bottom (Original / 1:1 / 4:5 / 16:9) — the
 *    window morphs on a spring while zoom/pan are preserved and re-clamped,
 *  - dark glassmorphic chrome: near-black canvas, translucent #12141D toolbars
 *    with hairline seams, accent-lit confirm (same Noir palette the uCrop
 *    theming used, so the editor stays dark in both light and dark mode),
 *  - EXIF-orientation-aware decode, downsampled to ≤2160px for a smooth
 *    preview; the final crop runs on that bitmap and saves a JPEG into the
 *    cache dir — the exact file contract the upload pipeline already consumes
 *    (a plain file Uri, like uCrop's output).
 *
 * All crop math is plain, deterministic mapping from window-space to bitmap
 * pixels (see [saveCrop]) — no third-party activity, no result-contract hops.
 */

/** Fixed Noir chrome for the always-dark immersive editor. */
private val CropCanvas = Color(0xFF06070C)
private val CropToolbarTop = Color(0xFF12141D).copy(alpha = 0.92f)
private val CropToolbarBottom = Color(0xFF12141D).copy(alpha = 0.72f)
private val CropAccent = Color(0xFF818CF8)
private val CropTextPrimary = Color(0xFFF4F5FA)
private val CropTextSecondary = Color(0xFFA9AEC0)
private val CropHairline = Color.White.copy(alpha = 0.10f)

/** Stage geometry shared by the draw pass, the gesture handler and the saver. */
private data class CropGeometry(
    val stageW: Float = 0f,
    val stageH: Float = 0f,
    val winW: Float = 0f,
    val winH: Float = 0f,
    /** Display pixels per source-bitmap pixel at zoom == 1 (covers window). */
    val base: Float = 0f,
    val bmpW: Int = 0,
    val bmpH: Int = 0
) {
    val isReady: Boolean get() = base > 0f && bmpW > 0 && bmpH > 0
}

private val CropRatioLabels = listOf("Original", "1:1", "4:5", "16:9")

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

    // Geometry lives in a state holder so the gesture coroutine and the draw
    // pass always read the CURRENT values without restarting pointerInput.
    val geometry = remember { mutableStateOf(CropGeometry()) }

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
                    .background(
                        Brush.verticalGradient(listOf(CropToolbarTop, CropToolbarBottom))
                    )
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
                            .background(Color.White.copy(alpha = 0.07f))
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

                    // Confirm — accent chip; morphs into a spinner while saving.
                    val canConfirm = bitmap != null && !isSaving
                    val confirmBg by animateColorAsState(
                        targetValue = if (canConfirm) CropAccent else Color.White.copy(alpha = 0.07f),
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
                                if (canConfirm) Color.White.copy(alpha = 0.25f) else CropHairline,
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

                // Hairline seam that fades at both ends — no harsh divider.
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

                    val targetRatio = when (ratioIndex) {
                        1 -> 1f
                        2 -> 4f / 5f
                        3 -> 16f / 9f
                        else -> bmp.width.toFloat() / bmp.height.toFloat()
                    }
                    // The window morphs on a spring — instantly responsive pills.
                    val animatedRatio by animateFloatAsState(
                        targetValue = targetRatio,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "CropRatioMorph"
                    )

                    var winW = stageW
                    var winH = winW / animatedRatio
                    if (winH > stageH) {
                        winH = stageH
                        winW = winH * animatedRatio
                    }
                    // zoom == 1 means the image exactly COVERS the window.
                    val base = max(winW / bmp.width.toFloat(), winH / bmp.height.toFloat())

                    geometry.value = CropGeometry(
                        stageW = stageW,
                        stageH = stageH,
                        winW = winW,
                        winH = winH,
                        base = base,
                        bmpW = bmp.width,
                        bmpH = bmp.height
                    )

                    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bmp) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val g = geometry.value
                                    if (!g.isReady) return@detectTransformGestures
                                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                    val maxX = clampX(g, newZoom)
                                    val maxY = clampY(g, newZoom)
                                    zoom = newZoom
                                    panOffset = Offset(
                                        x = (panOffset.x + pan.x).coerceIn(-maxX, maxX),
                                        y = (panOffset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val g = geometry.value
                            if (!g.isReady) return@Canvas

                            val imgW = g.bmpW * g.base * zoom
                            val imgH = g.bmpH * g.base * zoom
                            val ox = panOffset.x.coerceIn(-clampX(g, zoom), clampX(g, zoom))
                            val oy = panOffset.y.coerceIn(-clampY(g, zoom), clampY(g, zoom))
                            val imgLeft = (size.width - imgW) / 2f + ox
                            val imgTop = (size.height - imgH) / 2f + oy

                            // The image itself (scaled draw = smooth preview).
                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                                dstSize = IntSize(
                                    imgW.roundToInt().coerceAtLeast(1),
                                    imgH.roundToInt().coerceAtLeast(1)
                                )
                            )

                            // Dim everything outside the crop window.
                            val wl = (size.width - g.winW) / 2f
                            val wt = (size.height - g.winH) / 2f
                            val dim = Color.Black.copy(alpha = 0.72f)
                            drawRect(dim, topLeft = Offset(0f, 0f), size = size.copy(height = wt))
                            drawRect(
                                dim,
                                topLeft = Offset(0f, wt + g.winH),
                                size = size.copy(height = (size.height - wt - g.winH).coerceAtLeast(0f))
                            )
                            drawRect(dim, topLeft = Offset(0f, wt), size = size.copy(width = wl, height = g.winH))
                            drawRect(
                                dim,
                                topLeft = Offset(wl + g.winW, wt),
                                size = size.copy(
                                    width = (size.width - wl - g.winW).coerceAtLeast(0f),
                                    height = g.winH
                                )
                            )

                            // Rule-of-thirds grid, whisper-quiet.
                            val grid = Color.White.copy(alpha = 0.12f)
                            val gridStroke = 1.dp.toPx()
                            for (i in 1..2) {
                                val gx = wl + g.winW * i / 3f
                                drawLine(grid, Offset(gx, wt), Offset(gx, wt + g.winH), gridStroke)
                                val gy = wt + g.winH * i / 3f
                                drawLine(grid, Offset(wl, gy), Offset(wl + g.winW, gy), gridStroke)
                            }

                            // Hairline frame + L-shaped corner accents.
                            drawRect(
                                color = Color.White.copy(alpha = 0.35f),
                                topLeft = Offset(wl, wt),
                                size = size.copy(width = g.winW, height = g.winH),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                            val arm = 18.dp.toPx()
                            val accentWidth = 3.dp.toPx()
                            val accent = Color.White.copy(alpha = 0.60f)
                            // top-left
                            drawLine(accent, Offset(wl, wt), Offset(wl + arm, wt), accentWidth, StrokeCap.Round)
                            drawLine(accent, Offset(wl, wt), Offset(wl, wt + arm), accentWidth, StrokeCap.Round)
                            // top-right
                            drawLine(accent, Offset(wl + g.winW, wt), Offset(wl + g.winW - arm, wt), accentWidth, StrokeCap.Round)
                            drawLine(accent, Offset(wl + g.winW, wt), Offset(wl + g.winW, wt + arm), accentWidth, StrokeCap.Round)
                            // bottom-left
                            drawLine(accent, Offset(wl, wt + g.winH), Offset(wl + arm, wt + g.winH), accentWidth, StrokeCap.Round)
                            drawLine(accent, Offset(wl, wt + g.winH), Offset(wl, wt + g.winH - arm), accentWidth, StrokeCap.Round)
                            // bottom-right
                            drawLine(accent, Offset(wl + g.winW, wt + g.winH), Offset(wl + g.winW - arm, wt + g.winH), accentWidth, StrokeCap.Round)
                            drawLine(accent, Offset(wl + g.winW, wt + g.winH), Offset(wl + g.winW, wt + g.winH - arm), accentWidth, StrokeCap.Round)
                        }
                    }
                }
            }

            // ── Bottom glass bar: aspect pills ──────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(CropToolbarBottom, CropToolbarTop))
                    )
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
                            targetValue = if (active) CropAccent else Color.White.copy(alpha = 0.07f),
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
                                    if (active) Color.White.copy(alpha = 0.25f) else CropHairline,
                                    CircleShape
                                )
                                .clickable { ratioIndex = index }
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

/** How far the image center may travel on X at a given zoom (window stays covered). */
private fun clampX(g: CropGeometry, zoom: Float): Float =
    ((g.bmpW * g.base * zoom - g.winW) / 2f).coerceAtLeast(0f)

/** How far the image center may travel on Y at a given zoom (window stays covered). */
private fun clampY(g: CropGeometry, zoom: Float): Float =
    ((g.bmpH * g.base * zoom - g.winH) / 2f).coerceAtLeast(0f)

/**
 * Maps the on-screen crop window back to source-bitmap pixels and saves the
 * result as a JPEG in the cache dir — the same file-Uri contract uCrop used,
 * so the Supabase upload pipeline consumes it unchanged.
 */
private suspend fun saveCrop(
    context: android.content.Context,
    source: Bitmap,
    g: CropGeometry,
    zoom: Float,
    offset: Offset
): Uri? = withContext(Dispatchers.Default) {
    try {
        if (!g.isReady) return@withContext null

        val imgW = g.bmpW * g.base * zoom
        val imgH = g.bmpH * g.base * zoom
        val ox = offset.x.coerceIn(-clampX(g, zoom), clampX(g, zoom))
        val oy = offset.y.coerceIn(-clampY(g, zoom), clampY(g, zoom))
        val imgLeft = (g.stageW - imgW) / 2f + ox
        val imgTop = (g.stageH - imgH) / 2f + oy
        val winLeft = (g.stageW - g.winW) / 2f
        val winTop = (g.stageH - g.winH) / 2f

        val pxPerSrc = g.base * zoom
        val x = ((winLeft - imgLeft) / pxPerSrc).roundToInt().coerceIn(0, source.width - 1)
        val y = ((winTop - imgTop) / pxPerSrc).roundToInt().coerceIn(0, source.height - 1)
        val w = (g.winW / pxPerSrc).roundToInt().coerceIn(1, source.width - x)
        val h = (g.winH / pxPerSrc).roundToInt().coerceIn(1, source.height - y)

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
 * edge is ≤ 2160px (smooth gesture preview, plenty for feed upload), and
 * applies the EXIF orientation so phone photos are never sideways.
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
