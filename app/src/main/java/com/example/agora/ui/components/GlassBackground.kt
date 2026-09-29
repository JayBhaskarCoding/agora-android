package com.example.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
 * Hyper-modern, deep dynamic mesh/aurora gradient background with seamless blurred ambient color orbs.
 * Completely borderless, seamless, and free of any harsh card lines or chat artifacts.
 * Creates an ultra-premium depth field for iOS-style frosted glass cards to float over.
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
                brush = if (isDarkTheme) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF070B14),
                            Color(0xFF0F172A),
                            Color(0xFF130E26),
                            Color(0xFF030712)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFF1F5F9),
                            Color(0xFFE0E7FF),
                            Color(0xFFF5F3FF),
                            Color(0xFFE2E8F0)
                        )
                    )
                }
            )
    ) {
        if (isDarkTheme) {
            // Orb 1: Rich Electric Indigo (Top-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-40).dp, y = (-40).dp)
                    .size(340.dp)
                    .blur(110.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4F46E5).copy(alpha = 0.38f))
            )

            // Orb 2: Vibrant Deep Magenta / Violet (Center-Right)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 60.dp, y = (-20).dp)
                    .size(320.dp)
                    .blur(115.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF9333EA).copy(alpha = 0.30f))
            )

            // Orb 3: Luminous Cyan / Teal (Bottom-Center)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 60.dp)
                    .size(360.dp)
                    .blur(120.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF06B6D4).copy(alpha = 0.26f))
            )

            // Orb 4: Subtle Rose Accent (Bottom-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-50).dp, y = (-30).dp)
                    .size(240.dp)
                    .blur(95.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE11D48).copy(alpha = 0.20f))
            )
        } else {
            // Light Theme: Smooth, luminous pastel orbs
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-30).dp, y = (-30).dp)
                    .size(320.dp)
                    .blur(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF818CF8).copy(alpha = 0.35f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 40.dp)
                    .size(300.dp)
                    .blur(95.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFC084FC).copy(alpha = 0.30f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 40.dp)
                    .size(320.dp)
                    .blur(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.25f))
            )
        }

        content()
    }
}
