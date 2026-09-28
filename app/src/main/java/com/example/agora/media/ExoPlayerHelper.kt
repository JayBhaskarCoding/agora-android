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

                    // HTTP-level failures are the interesting ones here: a 403 from
                    // Catbox means bot protection rejected the request (User-Agent),
                    // not that the file is missing. The response body is usually a
                    // block page, which tells you which tier rejected us.
                    val httpError = error.cause as? HttpDataSource.InvalidResponseCodeException
                    if (httpError != null) {
                        val body = String(
                            httpError.responseBody,
                            0,
                            minOf(512, httpError.responseBody.size),
                            Charsets.UTF_8
                        )
                        Log.e(
                            TAG,
                            "Upstream HTTP ${httpError.responseCode} " +
                                "(${httpError.responseMessage}) for ${httpError.dataSpec.uri} — " +
                                "headers=${httpError.headerFields} — body=$body",
                            error
                        )
                    }
                }
            })
        }
    }
}
