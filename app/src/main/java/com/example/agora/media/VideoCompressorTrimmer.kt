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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.UUID

sealed class CompressionState {
    object Idle : CompressionState()
    data class Compressing(val progress: Float) : CompressionState()
    data class Success(val outputUri: Uri) : CompressionState()
    data class Error(val message: String) : CompressionState()
}

class VideoCompressorTrimmer(private val context: Context) {

    @OptIn(UnstableApi::class)
    fun trimAndCompressVideo(
        inputUri: Uri,
        startMs: Long,
        endMs: Long
    ): Flow<CompressionState> = callbackFlow {
        val outputFile = File(context.cacheDir, "trimmed_compressed_${UUID.randomUUID()}.mp4")
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
                    trySend(CompressionState.Success(outputUri))
                    close()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    trySend(CompressionState.Error(exportException.localizedMessage ?: "Video export failed"))
                    close()
                }
            })
            .build()

        try {
            trySend(CompressionState.Compressing(0.1f))
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            trySend(CompressionState.Error(e.localizedMessage ?: "Failed to start transformer"))
            close()
        }

        awaitClose {
            try {
                transformer.cancel()
            } catch (_: Exception) {}
        }
    }
}
