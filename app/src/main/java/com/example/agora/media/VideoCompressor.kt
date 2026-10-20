package com.example.agora.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class VideoCompressor(private val context: Context) {

    @OptIn(UnstableApi::class)
    suspend fun trimAndCompress(
        inputUri: Uri,
        startMs: Long,
        endMs: Long
    ): Uri? = suspendCancellableCoroutine { continuation ->
        val outputFile = File(context.cacheDir, "compressed_${UUID.randomUUID()}.mp4")
        val outputUri = Uri.fromFile(outputFile)

        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) {
                        continuation.resume(outputUri)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
            .build()

        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }

        continuation.invokeOnCancellation {
            try {
                transformer.cancel()
            } catch (_: Exception) {}
        }
    }
}
