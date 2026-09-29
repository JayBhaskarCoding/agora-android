package com.example.agora.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

fun Modifier.hazeChild(
    state: HazeState,
    shape: Shape,
    blurRadius: Dp = Dp.Unspecified
): Modifier = this
    .clip(shape)
    .hazeEffect(state = state) {
        if (blurRadius != Dp.Unspecified) {
            this.blurRadius = blurRadius
        }
    }

/**
 * Standard Design Tokens for Glassmorphism across Agora.
 */
object GlassTokens {
    val RadiusSmall: Dp = 12.dp
    val RadiusMedium: Dp = 18.dp
    val RadiusLarge: Dp = 24.dp
    val RadiusPill: Dp = 100.dp

    // Frosted surfaces
    val DarkSurface: Color = Color(0xFF0F172A).copy(alpha = 0.55f)
    val DarkSurfaceSubtle: Color = Color(0xFF1E293B).copy(alpha = 0.40f)
    val LightSurface: Color = Color.White.copy(alpha = 0.65f)
    val LightSurfaceSubtle: Color = Color.White.copy(alpha = 0.45f)

    // Glass borders (specular highlight on edges)
    val DarkBorder: Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.25f),
            Color.White.copy(alpha = 0.05f)
        )
    )

    val LightBorder: Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.70f),
            Color.White.copy(alpha = 0.20f)
        )
    )
}

/**
 * Applies a frosted glass surface style with background translucency,
 * specular edge lighting (border), and clipping.
 */
fun Modifier.glassmorphic(
    shape: Shape = RoundedCornerShape(GlassTokens.RadiusLarge),
    isDark: Boolean = true,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(
        brush = Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    Color(0xFF1E293B).copy(alpha = 0.65f),
                    Color(0xFF0F172A).copy(alpha = 0.75f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.75f),
                    Color.White.copy(alpha = 0.55f)
                )
            }
        ),
        shape = shape
    )
    .border(
        width = borderWidth,
        brush = if (isDark) GlassTokens.DarkBorder else GlassTokens.LightBorder,
        shape = shape
    )

/**
 * Frosted glass pill style ideal for action buttons, badge overlays, and tag chips.
 */
fun Modifier.glassPill(
    isDark: Boolean = true
): Modifier = this
    .clip(RoundedCornerShape(GlassTokens.RadiusPill))
    .background(
        color = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.60f),
        shape = RoundedCornerShape(GlassTokens.RadiusPill)
    )
    .border(
        width = 1.dp,
        brush = if (isDark) GlassTokens.DarkBorder else GlassTokens.LightBorder,
        shape = RoundedCornerShape(GlassTokens.RadiusPill)
    )
