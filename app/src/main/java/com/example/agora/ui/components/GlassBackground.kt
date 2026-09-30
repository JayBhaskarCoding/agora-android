package com.example.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * ✦ AGORA NOIR CANVAS
 *
 * The app-wide ambient background, matching the home feed exactly: a deep
 * obsidian vertical gradient (cool pearl in light mode) with soft aurora
 * washes painted as radial-gradient brushes — no runtime blur modifiers, so
 * the whole canvas costs a single raster pass.
 *
 * Name kept for source compatibility with the auth/onboarding screens.
 */
@Composable
fun VibrantGlassBackground(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isDarkTheme) {
                        listOf(
                            Color(0xFF0B0C13),
                            Color(0xFF080910),
                            Color(0xFF06070C)
                        )
                    } else {
                        listOf(
                            Color(0xFFF8F9FA),
                            Color(0xFFF4F5F7),
                            Color(0xFFF1F2F4)
                        )
                    }
                )
            )
    ) {
        if (isDarkTheme) {
            // Aurora I — indigo, top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-100).dp, y = (-80).dp)
                    .size(440.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color(0xFF6366F1).copy(alpha = 0.20f),
                                0.55f to Color(0xFF6366F1).copy(alpha = 0.08f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
            // Aurora II — violet, center-right
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 110.dp, y = 40.dp)
                    .size(400.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color(0xFF8B5CF6).copy(alpha = 0.14f),
                                0.55f to Color(0xFF8B5CF6).copy(alpha = 0.05f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
            // Aurora III — cyan, bottom-left
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-80).dp, y = 60.dp)
                    .size(420.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color(0xFF22D3EE).copy(alpha = 0.10f),
                                0.55f to Color(0xFF22D3EE).copy(alpha = 0.04f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
        } else {
            // Aurora I — indigo bloom, top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-90).dp, y = (-70).dp)
                    .size(400.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color(0xFFA5B4FC).copy(alpha = 0.34f),
                                0.6f to Color(0xFFA5B4FC).copy(alpha = 0.12f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
            // Aurora II — sky bloom, bottom-right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 90.dp, y = 40.dp)
                    .size(400.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color(0xFF7DD3FC).copy(alpha = 0.26f),
                                0.6f to Color(0xFF7DD3FC).copy(alpha = 0.10f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
        }

        content()
    }
}
