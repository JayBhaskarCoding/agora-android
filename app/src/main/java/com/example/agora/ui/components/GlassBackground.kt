package com.example.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Hyper-modern, deep dynamic aurora gradient background with blurred ambient color orbs,
 * tailored for high-end Glassmorphic / frosted glass UI surfaces.
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
                            Color(0xFF0A0F1D),
                            Color(0xFF0F172A),
                            Color(0xFF030712)
                        )
                    } else {
                        listOf(
                            Color(0xFFEFF6FF),
                            Color(0xFFF8FAFC),
                            Color(0xFFE2E8F0)
                        )
                    }
                )
            )
    ) {
        // Ambient Glowing Orbs
        if (isDarkTheme) {
            // Indigo / Violet Orb top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(280.dp)
                    .blur(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6366F1).copy(alpha = 0.32f))
            )

            // Cyan / Blue Orb middle-right
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(260.dp)
                    .blur(95.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF06B6D4).copy(alpha = 0.22f))
            )

            // Purple Orb bottom-left
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(300.dp)
                    .blur(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.28f))
            )
        } else {
            // Vibrant subtle orbs for light mode
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(280.dp)
                    .blur(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF818CF8).copy(alpha = 0.25f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(300.dp)
                    .blur(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.20f))
            )
        }

        content()
    }
}
