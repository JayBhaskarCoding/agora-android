package net.agora.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Client-side preparation of media **before** it is uploaded.
 *
 * The rule this enforces: the bytes that leave the device are already the bytes
 * a viewer needs. A 12 MP camera photo and a 50 Mbps 4K clip are never uploaded:
 *
 *  - images are decoded with an [BitmapFactory.Options.inSampleSize] downsample,
 *    rescaled so the longest edge is at most [MAX_IMAGE_EDGE] (1440 px) and
 *    re-encoded as WebP at quality [WEBP_QUALITY] (80);
 *  - videos go through Media3 [Transformer] — H.264 + AAC, height capped at
 *    [MAX_VIDEO_HEIGHT] (720) — which typically cuts a phone clip by 80–95 %.
 *
 * ── TEMP FILES ──────────────────────────────────────────────────────────────
 * The video pipeline is file-based (Transformer writes to disk). Every temp file
 * lives in `context.cacheDir` under a [TEMP_PREFIX], and is deleted in a
 * `finally` — success, failure and cancellation alike. [TempFileSweeper] covers
 * the one case a `finally` cannot: the process being killed mid-export.
 *
 * Failure is always non-fatal: if compression throws, the caller gets the
 * original bytes rather than a failed post.
 */
@OptIn(UnstableApi::class)
object MediaCompressor {

    private const val TAG = "MediaCompressor"

    /** Longest-edge cap for an uploaded photo. */
    const val MAX_IMAGE_EDGE = 1440

    /** Video height cap — 720p is the sweet spot for a social feed. */
    const val MAX_VIDEO_HEIGHT = 720

    private const val WEBP_QUALITY = 80

    /** Prefix of every temp file this object creates, so the sweeper can find them. */
    const val TEMP_PREFIX = "compressed_"

    // ── Images ──────────────────────────────────────────────────────────────

    /**
     * Downscales to [MAX_IMAGE_EDGE] and re-encodes as WebP.
     *
     * Uses `inSampleSize` first (cheap, in the decoder) and only then
     * `createScaledBitmap`, because decoding a 12 MP bitmap just to scale it is
     * how you get an `OutOfMemoryError` on a low-end device.
     */
    suspend fun compressImage(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sampleSize = 1
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                // Decode to no more than 2x the target; the final scale below is
                // then a cheap, high-quality downscale.
                while (longest / (sampleSize * 2) >= MAX_IMAGE_EDGE) sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: return@withContext readRaw(context, uri)

            val scaled = scaleToFit(decoded)
            val outWidth = scaled.width
            val outHeight = scaled.height
            val output = ByteArrayOutputStream()
            // WEBP_LOSSY exists from API 30; the deprecated WEBP constant is
            // lossy on every API level this app supports (minSdk 26).
            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            scaled.compress(format, WEBP_QUALITY, output)
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()

            val bytes = output.toByteArray()
            Log.d(TAG, "image ${bounds.outWidth}x${bounds.outHeight} -> ${outWidth}x${outHeight}, ${bytes.size / 1024} KB")
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "image compression failed, uploading original: ${e.localizedMessage}", e)
            readRaw(context, uri)
        }
    }

    private fun scaleToFit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_IMAGE_EDGE) return source
        val scale = MAX_IMAGE_EDGE.toFloat() / longest
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun readRaw(context: Context, uri: Uri): ByteArray =
        try {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: ByteArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "could not read $uri: ${e.localizedMessage}", e)
            ByteArray(0)
        }

    // ── Video ───────────────────────────────────────────────────────────────

    /**
     * Re-encodes to H.264/AAC with the height capped at [MAX_VIDEO_HEIGHT].
     *
     * The export target is a temp file in `cacheDir`; it is deleted in a
     * `finally` on every path (completed, errored, cancelled the coroutine).
     */
    suspend fun compressVideo(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val outputFile = File(
            appContext.cacheDir,
            "$TEMP_PREFIX${UUID.randomUUID()}.mp4"
        )

        try {
            val completed = exportToFile(appContext, uri, outputFile)
            if (completed && outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "video compressed -> ${outputFile.length() / 1024} KB")
                outputFile.readBytes()
            } else {
                // Compression is an optimisation, never a hard requirement: a
                // device without an H.264 encoder still gets to post.
                Log.w(TAG, "video compression unavailable, uploading original")
                readRaw(appContext, uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "video compression failed: ${e.localizedMessage}", e)
            readRaw(appContext, uri)
        } finally {
            // 🌟 The only cleanup that is guaranteed to run. Note it is a single
            //    place: earlier code deleted on success and on failure separately,
            //    which missed the cancellation path.
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "temp file could not be deleted: ${outputFile.name}")
            }
        }
    }

    private suspend fun exportToFile(
        context: Context,
        input: Uri,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(
                Effects(
                    /* audio processors = */ emptyList(),
                    /* video effects = */ listOf(
                        Presentation.createForHeight(MAX_VIDEO_HEIGHT)
                    )
                )
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.w(TAG, "Transformer failed: ${exportException.message}", exportException)
                    if (continuation.isActive) continuation.resume(false)
                }
            })
            .build()

        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Transformer could not start: ${e.localizedMessage}", e)
            if (continuation.isActive) continuation.resume(false)
        }

        continuation.invokeOnCancellation {
            // Best effort: stop the export so the temp file stops growing. The
            // caller's `finally` still deletes it.
            try {
                transformer.cancel()
            } catch (_: Exception) {
            }
        }
    }
}
