package com.example.agora.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis

@Composable
fun VideoThumbnail(
    videoUri: Uri,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current

    val imageRequest = remember(context, videoUri) {
        ImageRequest.Builder(context)
            .data(videoUri)
            .decoderFactory(VideoFrameDecoder.Factory())
            .videoFrameMillis(1000) // 1-second mark to avoid black opening frame
            .crossfade(true)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = "Video Thumbnail",
        modifier = modifier.fillMaxSize(),
        contentScale = contentScale
    )
}
