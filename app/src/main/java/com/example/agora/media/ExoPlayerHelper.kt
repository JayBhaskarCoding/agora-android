package com.example.agora.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@OptIn(UnstableApi::class)
object ExoPlayerHelper {

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

        return ExoPlayer.Builder(context, renderersFactory).build()
    }
}
