package com.example.agora.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.agora.R
import com.example.agora.ui.theme.LocalDarkTheme

@Composable
fun AgoraBackground(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = LocalDarkTheme.current,
    blurRadius: Dp = 7.dp,
    content: @Composable () -> Unit
) {
    val bgDrawableRes = if (isDarkTheme) {
        R.drawable.app_background_dark
    } else {
        R.drawable.app_background_light
    }

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = bgDrawableRes),
            contentDescription = "App Background Wallpaper",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .blur(radius = blurRadius)
                .fillMaxSize()
        )
        content()
    }
}
