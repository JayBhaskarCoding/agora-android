package com.example.agora.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.io.IOException

@OptIn(UnstableApi::class)
object ExoPlayerHelper {

    private const val TAG = "ExoPlayerHelper"

    fun createExoPlayer(context: Context): ExoPlayer {
        // 🌟 Custom DefaultRenderersFactory with automatic decoder fallback & Unisoc codec handling
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true) // 🌟 Automatically falls back from hardware to software decoder on CodecException
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )

                // 🌟 Special handling for AVC / H.264 video decoding on Unisoc chipsets
                if (MimeTypes.VIDEO_H264.equals(mimeType, ignoreCase = true)) {
                    val hasUnisocDecoder = decoders.any { it.name.contains("unisoc", ignoreCase = true) }

                    if (hasUnisocDecoder) {
                        // Place standard stable software decoder (c2.android.avc.decoder) first when Unisoc decoder is present
                        return@setMediaCodecSelector decoders.sortedBy { info ->
                            if (info.name.contains("unisoc", ignoreCase = true)) 1 else 0
                        }
                    }
                }
                decoders
            }

        return ExoPlayer.Builder(context, renderersFactory).build().apply {
            // Every player in the app funnels through here, so one listener covers the
            // feed pool, the inline post player, the fullscreen player and the trimmer.
            // Without this, a failed upstream request is indistinguishable from a slow
            // network — the surface is simply black.
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(
                        TAG,
                        "Playback error: ${error.errorCodeName} (code=${error.errorCode}) — " +
                            "message=${error.message} — cause=${error.cause?.javaClass?.simpleName}: " +
                            "${error.cause?.message}",
                        error
                    )

                    // Every HTTP-level failure funnels through HttpDataSourceException, so one
                    // cast gives us the failing URL (for the network probe below); the status
                    // code exists only on InvalidResponseCodeException. The distinction matters:
                    // a 4xx/5xx means the host answered (bot protection, missing file), while an
                    // exception *without* a status means the request never got a reply at all.
                    val httpError = error.cause as? HttpDataSource.HttpDataSourceException
                    val statusError = httpError as? HttpDataSource.InvalidResponseCodeException
                    if (statusError != null) {
                        val body = String(
                            statusError.responseBody,
                            0,
                            minOf(512, statusError.responseBody.size),
                            Charsets.UTF_8
                        )
                        Log.e(
                            TAG,
                            "Upstream answered HTTP ${statusError.responseCode} " +
                                "(${statusError.responseMessage}) for ${statusError.dataSpec.uri} " +
                                "— headers=${statusError.headerFields} — body=$body",
                            error
                        )
                    } else if (httpError != null) {
                        Log.e(
                            TAG,
                            "Upstream never answered for ${httpError.dataSpec.uri} — " +
                                "transport-level failure, no HTTP status was received.",
                            error
                        )
                    }

                    // The failing URL lives on the DataSpec of whichever data source threw, not
                    // on the player, so read it from the exception instead of currentMediaItem
                    // (which may already have moved on). Debounced internally.
                    // Skipped when the root cause is a caller-initiated cancellation
                    // (release/seek race): there is no network fault to probe.
                    val isCallerCancellation = generateSequence<Throwable>(error.cause) { it.cause }
                        .any { it is IOException && it.message == "Canceled" }
                    if (!isCallerCancellation) {
                        httpError?.dataSpec?.uri?.toString()?.let(MediaHttpClient::diagnoseOnFailure)
                    }
                }
            })
        }
    }
}
