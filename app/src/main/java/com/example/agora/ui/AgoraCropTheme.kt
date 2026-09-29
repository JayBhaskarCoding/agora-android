package com.example.agora.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.model.AspectRatio

/**
 * Noir chrome for the uCrop photo editor.
 *
 * uCrop renders its own AppCompat activity, so Compose theming can't reach it —
 * every color is pushed through [UCrop.Options] intent extras, backed by the
 * dark `Theme.Agora.UCrop` declared for `UCropActivity` in the manifest.
 * The editor is an immersive surface, so it stays dark regardless of the app's
 * light/dark mode (same convention as Instagram-style croppers), using the
 * exact Noir palette values: canvas `#0B0C13`, toolbar `#12141D`,
 * accent `#818CF8`, hairline-white crop chrome.
 *
 * STYLING ONLY — crop mathematics, gesture behavior and file saving remain
 * uCrop's stock pipeline; the caller still owns source/destination URIs.
 */
fun agoraCropOptions(): UCrop.Options = UCrop.Options().apply {
    // Sleek ratio pills: Free (active by default, free-form drag allowed), 1:1, 4:5, 16:9.
    setFreeStyleCropEnabled(true)
    setAspectRatioOptions(
        0,
        AspectRatio("Free", 0f, 0f),
        AspectRatio("1:1", 1f, 1f),
        AspectRatio("4:5", 4f, 5f),
        AspectRatio("16:9", 16f, 9f)
    )

    // Deep Noir canvas behind everything + true-black status bar.
    setRootViewBackgroundColor(Color(0xFF0B0C13).toArgb())
    setStatusBarColor(Color(0xFF000000).toArgb())

    // Toolbar: dark glass slab with light icons/title (replaces the stock white bar).
    setToolbarColor(Color(0xFF12141D).toArgb())
    setToolbarWidgetColor(Color(0xFFF4F5FA).toArgb())
    setToolbarTitle("Edit Photo")

    // Brand accent drives the ACTIVE ratio pill + rotate/scale control icons.
    setActiveControlsWidgetColor(Color(0xFF818CF8).toArgb())

    // Crop chrome: hairline frame, faint grid, deep dim outside the crop bounds.
    setDimmedLayerColor(Color.Black.copy(alpha = 0.70f).toArgb())
    setShowCropFrame(true)
    setCropFrameColor(Color.White.copy(alpha = 0.22f).toArgb())
    setShowCropGrid(true)
    setCropGridColor(Color.White.copy(alpha = 0.12f).toArgb())

    // Hide the uCrop watermark logo — nothing generic on screen.
    setLogoColor(Color.Transparent.toArgb())
}
