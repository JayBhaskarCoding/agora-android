package com.example.agora.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
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
 * ✦ Design tokens — Noir pass.
 *
 * The names are kept for source compatibility, but the values now describe the
 * solid, sculpted surfaces of the flagship feed design: opaque cards, hairline
 * borders and a faint top sheen instead of translucent frosted glass.
 */
object GlassTokens {
    val RadiusSmall: Dp = 12.dp
    val RadiusMedium: Dp = 18.dp
    val RadiusLarge: Dp = 28.dp
    val RadiusPill: Dp = 100.dp

    // Solid Noir surfaces
    val DarkSurface: Color = Color(0xFF12141D)
    val DarkSurfaceSubtle: Color = Color(0xFF1C1F2B)
    val LightSurface: Color = Color(0xFFFFFFFF)
    val LightSurfaceSubtle: Color = Color(0xFFF1F2F4)

    // Hairline edges
    val DarkBorder: Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.10f),
            Color.White.copy(alpha = 0.06f)
        )
    )

    val LightBorder: Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFE3E4E9),
            Color(0xFFEDEEF2)
        )
    )

    // Top specular sheen (dark mode only — invisible on white cards)
    val DarkSheen: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = 0.045f),
            0.16f to Color.Transparent
        )
    )
}

/**
 * Applies the Noir card finish: an opaque surface with a faint top sheen and a
 * hairline border, clipped to [shape]. Drop-in replacement for the former
 * frosted-glass treatment — every screen using it now matches the feed cards.
 */
fun Modifier.glassmorphic(
    shape: Shape = RoundedCornerShape(GlassTokens.RadiusLarge),
    isDark: Boolean = true,
    borderWidth: Dp = 1.dp
): Modifier {
    var mod = this
        .clip(shape)
        .background(
            color = if (isDark) GlassTokens.DarkSurface else GlassTokens.LightSurface,
            shape = shape
        )
    if (isDark) {
        mod = mod.background(brush = GlassTokens.DarkSheen, shape = shape)
    }
    return mod.border(
        width = borderWidth,
        brush = if (isDark) GlassTokens.DarkBorder else GlassTokens.LightBorder,
        shape = shape
    )
}

/**
 * Noir pill finish for action buttons, badge overlays and tag chips: a solid
 * inset surface with a hairline edge.
 */
fun Modifier.glassPill(
    isDark: Boolean = true
): Modifier = this
    .clip(RoundedCornerShape(GlassTokens.RadiusPill))
    .background(
        color = if (isDark) GlassTokens.DarkSurfaceSubtle else GlassTokens.LightSurfaceSubtle,
        shape = RoundedCornerShape(GlassTokens.RadiusPill)
    )
    .border(
        width = 1.dp,
        brush = if (isDark) GlassTokens.DarkBorder else GlassTokens.LightBorder,
        shape = RoundedCornerShape(GlassTokens.RadiusPill)
    )
